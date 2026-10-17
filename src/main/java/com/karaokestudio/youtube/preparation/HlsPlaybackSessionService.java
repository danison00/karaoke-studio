package com.karaokestudio.youtube.preparation;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import com.karaokestudio.web.RequestLimiter;
import com.karaokestudio.youtube.JavaTubeResolver;
import com.karaokestudio.youtube.ResolvedYoutubeMedia;
import com.karaokestudio.youtube.YoutubeMediaService;
import com.karaokestudio.youtube.YoutubeUrlValidator;
import com.karaokestudio.youtube.YoutubeUrl;
import com.karaokestudio.youtube.ffmpeg.CommandResult;
import com.karaokestudio.youtube.ffmpeg.FfmpegCapabilities;
import com.karaokestudio.youtube.ffmpeg.FfmpegHlsProfile;
import com.karaokestudio.youtube.ffmpeg.FfmpegProcess;
import com.karaokestudio.youtube.ffmpeg.FfmpegRunner;

public class HlsPlaybackSessionService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HlsPlaybackSessionService.class);

    private final YoutubeUrlValidator urlValidator;
    private final JavaTubeResolver resolver;
    private final RequestLimiter requestLimiter;
    private final FfmpegRunner ffmpegRunner;
    private final FfmpegCapabilities ffmpegCapabilities;
    private final Executor executor;
    private final ExecutorService monitorExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final HlsMediaCache cache;
    private final HlsPlaylistReader playlistReader;
    private final YoutubeRelayRegistry relayRegistry;
    private final YoutubeMediaService mediaService;
    private final Duration timeout;
    private final long maxInputBytes;
    private final long maxDurationMs;
    private final Map<UUID, HlsPlaybackSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, UUID> byVideoId = new ConcurrentHashMap<>();
    private final Map<UUID, RequestLimiter.Lease> leases = new ConcurrentHashMap<>();
    private final java.util.Set<String> loggedPlayableGenerations = ConcurrentHashMap.newKeySet();

    public HlsPlaybackSessionService(
            YoutubeUrlValidator urlValidator,
            JavaTubeResolver resolver,
            RequestLimiter requestLimiter,
            FfmpegRunner ffmpegRunner,
            FfmpegCapabilities ffmpegCapabilities,
            Executor executor,
            HlsMediaCache cache,
            HlsPlaylistReader playlistReader,
            YoutubeRelayRegistry relayRegistry,
            YoutubeMediaService mediaService,
            Duration timeout,
            long maxInputBytes,
            long maxDurationMs) {
        this.urlValidator = Objects.requireNonNull(urlValidator);
        this.resolver = Objects.requireNonNull(resolver);
        this.requestLimiter = Objects.requireNonNull(requestLimiter);
        this.ffmpegRunner = Objects.requireNonNull(ffmpegRunner);
        this.ffmpegCapabilities = Objects.requireNonNull(ffmpegCapabilities);
        this.executor = Objects.requireNonNull(executor);
        this.cache = Objects.requireNonNull(cache);
        this.playlistReader = Objects.requireNonNull(playlistReader);
        this.relayRegistry = Objects.requireNonNull(relayRegistry);
        this.mediaService = mediaService;
        this.timeout = Objects.requireNonNull(timeout);
        if (maxInputBytes <= 0 || maxDurationMs <= 0) throw new IllegalArgumentException("Limites HLS inválidos");
        this.maxInputBytes = maxInputBytes;
        this.maxDurationMs = maxDurationMs;
    }

    public HlsPreparationSnapshot start(String rawUrl, String clientIp) {
        var youtubeUrl = urlValidator.validate(rawUrl);
        synchronized (byVideoId) {
            var existingId = byVideoId.get(youtubeUrl.videoId());
            if (existingId != null && sessions.containsKey(existingId)) return get(existingId);

            var lease = requestLimiter.acquire(clientIp);
            var id = UUID.randomUUID();
            var session = new HlsPlaybackSession(id, youtubeUrl.videoId(), youtubeUrl.videoId(), 0);
            sessions.put(id, session);
            byVideoId.put(youtubeUrl.videoId(), id);
            leases.put(id, lease);
            log.info("youtube.hls.prepare.accepted sessionId={} videoId={} clientIp={}", id, youtubeUrl.videoId(), clientIp);
            try {
                executor.execute(() -> resolveAndStart(session, youtubeUrl));
            } catch (RejectedExecutionException exception) {
                remove(id, session);
                throw new com.karaokestudio.web.RequestLimitException("Fila de reprodução cheia");
            }
            return session.snapshot();
        }
    }

    public HlsPreparationSnapshot get(UUID id) {
        var session = sessions.get(id);
        if (session == null) throw new MediaPreparationException("Preparação não encontrada ou expirada");
        return session.snapshot();
    }

    public HlsPreparationSnapshot seek(UUID id, long positionMs, long requestSequence) {
        var session = require(id);
        if (positionMs < 0 || requestSequence < 0) throw new IllegalArgumentException("Seek inválido");
        var decision = session.acceptSeek(requestSequence, positionMs);
        if (decision.cancelGeneration() != null) {
            var process = session.currentProcess();
            if (process != null) process.cancel();
            relayRegistry.cancel(id, decision.cancelGeneration());
        }
        if (decision.startGeneration() != null) {
            startGeneration(session, decision.startGeneration(), decision.positionMs());
        }
        return session.snapshot();
    }

    public HlsResource openHls(UUID id, long generation, String resourceName) {
        require(id);
        var path = cache.resolveResource(id, generation, resourceName)
                .orElseThrow(() -> new MediaNotReadyException("O recurso HLS ainda não está disponível"));
        try {
            var contentType = switch (resourceName) {
                case "index.m3u8" -> "application/vnd.apple.mpegurl";
                case "init.mp4" -> "video/mp4";
                default -> "video/iso.segment";
            };
            return new HlsResource(contentType, Files.size(path), Files.newInputStream(path));
        } catch (IOException exception) {
            throw new MediaNotReadyException("Não foi possível abrir o recurso HLS");
        }
    }

    public YoutubeMediaService.MediaStream openProgressive(UUID id, String range, String clientIp) {
        var session = require(id);
        if (!session.isProgressive() || mediaService == null) {
            throw new MediaNotReadyException("A preparação não é progressiva");
        }
        return mediaService.open(PreparedMedia.progressive(session.media()), range, clientIp);
    }

    public void delete(UUID id) {
        var session = sessions.remove(id);
        if (session == null) return;
        remove(id, session);
    }

    @Scheduled(fixedDelayString = "${app.youtube.cleanup-interval:5m}")
    public void cleanup() {
        sessions.entrySet().stream().filter(entry -> entry.getValue().closed()).toList()
                .forEach(entry -> remove(entry.getKey(), entry.getValue()));
        relayRegistry.cleanupExpired();
    }

    @Override
    public void close() {
        List.copyOf(sessions.keySet()).forEach(this::delete);
        monitorExecutor.shutdownNow();
    }

    private void resolveAndStart(HlsPlaybackSession session, YoutubeUrl youtubeUrl) {
        var startedAt = System.nanoTime();
        log.info("youtube.hls.resolve.start sessionId={} videoId={}", session.id(), youtubeUrl.videoId());
        try {
            var media = resolver.resolve(youtubeUrl.uri());
            log.info("youtube.hls.resolve.success sessionId={} videoId={} mode={} durationMs={} videoType={} audioType={} videoCodec={} audioCodec={}",
                    session.id(), youtubeUrl.videoId(), media.mode(), media.durationMs(),
                    contentType(media.videoStream()), contentType(media.audioStream()),
                    videoCodec(media.videoStream()), audioCodec(media.audioStream()));
            if (media.durationMs() > maxDurationMs) throw new MediaTooLargeException("A duração da mídia excede o limite configurado");
            session.resolved(media);
            if (media.mode() == ResolvedYoutubeMedia.Mode.PROGRESSIVE) {
                session.progressiveReady(media);
                closeLease(session.id());
                log.info("youtube.hls.resolve.progressive-ready sessionId={} elapsedMs={}", session.id(), elapsedMs(startedAt));
                return;
            }
            log.info("youtube.hls.ffmpeg.capabilities sessionId={} available={} supportsH264={} supportsAac={} version={} diagnostics={}",
                    session.id(), ffmpegCapabilities.available(), ffmpegCapabilities.supportsH264(),
                    ffmpegCapabilities.supportsAac(), ffmpegCapabilities.version(),
                    safeText(ffmpegCapabilities.diagnostics()));
            if (!ffmpegCapabilities.available()) throw new com.karaokestudio.youtube.ffmpeg.FfmpegUnavailableException("FFmpeg não está disponível no servidor");
            if (transcode(media) && (!ffmpegCapabilities.supportsH264() || !ffmpegCapabilities.supportsAac())) {
                throw new com.karaokestudio.youtube.ffmpeg.FfmpegUnavailableException("FFmpeg não possui os encoders H.264/AAC necessários");
            }
            startGeneration(session, 0, 0);
        } catch (Exception exception) {
            log.error("youtube.hls.resolve.failed sessionId={} videoId={} exceptionType={} message={} elapsedMs={}",
                    session.id(), youtubeUrl.videoId(), exception.getClass().getName(),
                    safeText(exception.getMessage()), elapsedMs(startedAt), exception);
            session.setError(sanitize(exception));
            closeLease(session.id());
        }
    }

    private void startGeneration(HlsPlaybackSession session, long generation, long positionMs) {
        if (session.closed()) return;
        var media = session.media();
        var profile = transcode(media) ? FfmpegHlsProfile.TRANSCODE : FfmpegHlsProfile.COPY;
        var directory = cache.createGeneration(session.id(), generation);
        var playlistUrl = "/api/youtube/preparations/" + session.id() + "/hls/" + generation + "/index.m3u8";
        var relays = relayRegistry.register(session.id(), generation, media.videoStream(), media.audioStream());
        log.info("youtube.hls.generation.start sessionId={} generation={} positionMs={} profile={} directory={}",
                session.id(), generation, positionMs, profile, directory);
        session.beginProducer(generation, null, playlistUrl);
        try {
            var process = ffmpegRunner.startHls(relays.video(), relays.audio(), directory, profile, positionMs, timeout);
            session.attachProcess(process);
            log.info("youtube.hls.ffmpeg.started sessionId={} generation={} profile={} positionMs={} alive={}",
                    session.id(), generation, profile, positionMs, process.isAlive());
            monitorExecutor.execute(() -> monitor(session, generation, directory, playlistUrl, process, positionMs));
        } catch (RuntimeException exception) {
            log.error("youtube.hls.ffmpeg.start-failed sessionId={} generation={} profile={} positionMs={} exceptionType={} message={}",
                    session.id(), generation, profile, positionMs, exception.getClass().getName(),
                    safeText(exception.getMessage()), exception);
            relayRegistry.cancel(session.id(), generation);
            session.producerFailed(generation, sanitize(exception));
        }
    }

    private void monitor(HlsPlaybackSession session, long generation, java.nio.file.Path directory,
            String playlistUrl, FfmpegProcess process, long positionMs) {
        while (session.isCurrentProducer(generation) && process.isAlive()) {
            inspect(session, generation, directory, playlistUrl, positionMs);
            try { Thread.sleep(150); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); return; }
        }
        if (!session.isCurrentProducer(generation)) return;
        inspect(session, generation, directory, playlistUrl, positionMs);
        boolean successful = false;
        String failure = null;
        CommandResult result = null;
        try {
            result = process.completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
            successful = result != null && result.exitCode() == 0;
            if (!successful) failure = ffmpegFailure(result);
            log.info("youtube.hls.ffmpeg.finished sessionId={} generation={} exitCode={} successful={} stderr={}",
                    session.id(), generation, result == null ? -1 : result.exitCode(), successful,
                    safeText(result == null ? null : result.stderr()));
        } catch (Exception exception) {
            failure = sanitize(exception);
            log.error("youtube.hls.ffmpeg.completion-failed sessionId={} generation={} exceptionType={} message={}",
                    session.id(), generation, exception.getClass().getName(), safeText(exception.getMessage()), exception);
        }
        log.warn("youtube.hls.generation.ended sessionId={} generation={} successful={} failure={} files={}",
                session.id(), generation, successful, safeText(failure), describeGeneration(directory));
        session.producerFinished(generation, successful, failure);
        relayRegistry.cancel(session.id(), generation);
    }

    private void inspect(HlsPlaybackSession session, long generation, java.nio.file.Path directory,
            String playlistUrl, long positionMs) {
        playlistReader.inspect(directory, generation, positionMs, playlistUrl).ifPresent(snapshot -> {
            if (loggedPlayableGenerations.add(session.id() + ":" + generation)) {
                log.info("youtube.hls.playlist.ready sessionId={} generation={} fromMs={} toMs={} ended={} playlist={}",
                        session.id(), generation, snapshot.fromMs(), snapshot.toMs(), snapshot.ended(), playlistUrl);
            }
            session.publish(snapshot);
        });
    }

    private HlsPlaybackSession require(UUID id) {
        var session = sessions.get(id);
        if (session == null) throw new MediaPreparationException("Preparação não encontrada ou expirada");
        return session;
    }

    private boolean transcode(ResolvedYoutubeMedia media) {
        var videoType = media.videoStream().contentType() == null ? "" : media.videoStream().contentType().toLowerCase(java.util.Locale.ROOT);
        var audioType = media.audioStream().contentType() == null ? "" : media.audioStream().contentType().toLowerCase(java.util.Locale.ROOT);
        var videoCodec = media.videoStream().videoCodec() == null ? "" : media.videoStream().videoCodec().toLowerCase(java.util.Locale.ROOT);
        var audioCodec = media.audioStream().audioCodec() == null ? "" : media.audioStream().audioCodec().toLowerCase(java.util.Locale.ROOT);
        return !videoType.startsWith("video/mp4") || !audioType.startsWith("audio/mp4")
                || !(videoCodec.isBlank() || videoCodec.contains("avc1") || videoCodec.contains("avc3"))
                || !(audioCodec.isBlank() || audioCodec.contains("mp4a") || audioCodec.contains("aac"));
    }

    private String sanitize(Exception exception) {
        if (exception instanceof com.karaokestudio.youtube.ffmpeg.FfmpegUnavailableException) return "FFmpeg não está disponível para preparar este vídeo.";
        if (exception instanceof MediaTooLargeException) return "A mídia excede os limites configurados.";
        return "Não foi possível preparar o vídeo do YouTube.";
    }

    private String ffmpegFailure(CommandResult result) {
        var exitCode = result == null ? -1 : result.exitCode();
        var stderr = result == null ? null : safeText(result.stderr());
        return stderr == null || stderr.isBlank()
                ? "FFmpeg encerrou com código " + exitCode
                : "FFmpeg encerrou com código " + exitCode + ": " + stderr;
    }

    private String contentType(com.karaokestudio.youtube.YoutubeStream stream) {
        return stream == null ? "-" : safeText(stream.contentType());
    }

    private String videoCodec(com.karaokestudio.youtube.YoutubeStream stream) {
        return stream == null ? "-" : safeText(stream.videoCodec());
    }

    private String audioCodec(com.karaokestudio.youtube.YoutubeStream stream) {
        return stream == null ? "-" : safeText(stream.audioCodec());
    }

    private long elapsedMs(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private String safeText(String value) {
        if (value == null || value.isBlank()) return value;
        var sanitized = value.replaceAll("https?://[^\\s]+", "[url-redacted]");
        return sanitized.length() <= 4096 ? sanitized : sanitized.substring(0, 4096);
    }

    private String describeGeneration(Path directory) {
        if (directory == null || !Files.exists(directory)) return "<missing>";
        try (var paths = Files.list(directory)) {
            var files = paths.sorted().map(path -> {
                try {
                    return path.getFileName() + "(" + Files.size(path) + "B)";
                } catch (IOException exception) {
                    return path.getFileName() + "(?)";
                }
            }).toList().toString();
            var playlist = directory.resolve("index.m3u8");
            if (!Files.isRegularFile(playlist)) return files;
            var content = safeText(Files.readString(playlist));
            return files + " playlist=" + content;
        } catch (IOException exception) {
            return "<unreadable:" + safeText(exception.getMessage()) + ">";
        }
    }

    private void remove(UUID id, HlsPlaybackSession session) {
        session.close();
        relayRegistry.discardSession(id);
        cache.discardSession(id);
        closeLease(id);
        byVideoId.remove(session.snapshot().videoId(), id);
        sessions.remove(id, session);
        loggedPlayableGenerations.removeIf(key -> key.startsWith(id + ":"));
    }

    private void closeLease(UUID id) {
        var lease = leases.remove(id);
        if (lease != null) lease.close();
    }

    public record HlsResource(String contentType, long contentLength, java.io.InputStream body) implements AutoCloseable {
        @Override public void close() throws IOException { body.close(); }
    }
}
