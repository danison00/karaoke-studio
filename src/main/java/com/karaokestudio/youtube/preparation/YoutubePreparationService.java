package com.karaokestudio.youtube.preparation;

import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.RejectedExecutionException;

import org.springframework.scheduling.annotation.Scheduled;

import com.karaokestudio.web.RequestLimiter;
import com.karaokestudio.youtube.JavaTubeResolver;
import com.karaokestudio.youtube.ResolvedYoutubeMedia;
import com.karaokestudio.youtube.YoutubeUrlValidator;
import com.karaokestudio.youtube.YoutubeResolutionException;
import com.karaokestudio.youtube.ffmpeg.FfmpegCapabilities;
import com.karaokestudio.youtube.ffmpeg.FfmpegRunner;
import com.karaokestudio.youtube.ffmpeg.FfmpegUnavailableException;
import com.karaokestudio.youtube.ffmpeg.MuxProfile;

public class YoutubePreparationService {

    private final YoutubeUrlValidator urlValidator;
    private final JavaTubeResolver resolver;
    private final RequestLimiter requestLimiter;
    private final SecureStreamDownloader downloader;
    private final YoutubeMediaCache cache;
    private final FfmpegRunner ffmpegRunner;
    private final FfmpegCapabilities ffmpegCapabilities;
    private final Executor executor;
    private final Semaphore preparationSlots;
    private final Duration timeout;
    private final long maxInputBytes;
    private final long maxDurationMs;
    private final Map<UUID, YoutubePreparation> preparations = new ConcurrentHashMap<>();
    private final Map<String, UUID> byVideoId = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> terminalAt = new ConcurrentHashMap<>();

    public YoutubePreparationService(
            YoutubeUrlValidator urlValidator,
            JavaTubeResolver resolver,
            RequestLimiter requestLimiter,
            SecureStreamDownloader downloader,
            YoutubeMediaCache cache,
            FfmpegRunner ffmpegRunner,
            FfmpegCapabilities ffmpegCapabilities,
            Executor executor,
            int activeWorkers,
            int maxQueue,
            Duration timeout,
            long maxInputBytes,
            long maxDurationMs) {
        this.urlValidator = Objects.requireNonNull(urlValidator);
        this.resolver = Objects.requireNonNull(resolver);
        this.requestLimiter = Objects.requireNonNull(requestLimiter);
        this.downloader = Objects.requireNonNull(downloader);
        this.cache = Objects.requireNonNull(cache);
        this.ffmpegRunner = Objects.requireNonNull(ffmpegRunner);
        this.ffmpegCapabilities = Objects.requireNonNull(ffmpegCapabilities);
        this.executor = Objects.requireNonNull(executor);
        if (activeWorkers <= 0 || maxQueue < 0) {
            throw new IllegalArgumentException("Os limites de preparação são inválidos");
        }
        this.preparationSlots = new Semaphore(activeWorkers + maxQueue);
        this.timeout = Objects.requireNonNull(timeout);
        this.maxInputBytes = positive(maxInputBytes, "maxInputBytes");
        this.maxDurationMs = positive(maxDurationMs, "maxDurationMs");
    }

    public YoutubePreparationSnapshot start(String rawUrl, String clientIp) {
        var youtubeUrl = urlValidator.validate(rawUrl);
        synchronized (byVideoId) {
            var existingId = byVideoId.get(youtubeUrl.videoId());
            if (existingId != null) {
                var existing = preparations.get(existingId);
                if (existing != null) {
                    return existing.snapshot();
                }
                byVideoId.remove(youtubeUrl.videoId(), existingId);
            }

            var lease = requestLimiter.acquire(clientIp);
            ResolvedYoutubeMedia media;
            try {
                media = resolver.resolve(youtubeUrl.uri());
                if (media.durationMs() > maxDurationMs) {
                    throw new MediaTooLargeException("A duração da mídia excede o limite configurado");
                }
                if (media.mode() == ResolvedYoutubeMedia.Mode.ADAPTIVE && !ffmpegCapabilities.available()) {
                    throw new FfmpegUnavailableException("FFmpeg não está disponível no servidor");
                }
            } catch (YoutubeResolutionException exception) {
                lease.close();
                if (exception.getMessage() != null && exception.getMessage().contains("Nenhum par de vídeo e áudio compatível")) {
                    throw new NoCompatibleStreamsException("Nenhum par de vídeo e áudio compatível foi encontrado para o vídeo");
                }
                throw exception;
            } catch (RuntimeException exception) {
                lease.close();
                throw exception;
            }

            var preparation = new YoutubePreparation(UUID.randomUUID(), media);
            preparations.put(preparation.id(), preparation);
            byVideoId.put(media.videoId(), preparation.id());
            if (media.mode() == ResolvedYoutubeMedia.Mode.PROGRESSIVE) {
                preparation.ready();
                terminalAt.put(preparation.id(), Instant.now());
                lease.close();
                return preparation.snapshot();
            }

            if (!preparationSlots.tryAcquire()) {
                preparations.remove(preparation.id());
                byVideoId.remove(media.videoId(), preparation.id());
                lease.close();
                throw new com.karaokestudio.web.RequestLimitException("Fila de preparação cheia");
            }
            try {
                executor.execute(() -> prepare(preparation, media, lease));
            } catch (RejectedExecutionException exception) {
                preparationSlots.release();
                preparations.remove(preparation.id());
                byVideoId.remove(media.videoId(), preparation.id());
                lease.close();
                throw new com.karaokestudio.web.RequestLimitException("Fila de preparação cheia");
            }
            return preparation.snapshot();
        }
    }

    public YoutubePreparationSnapshot get(UUID id) {
        var preparation = preparations.get(id);
        if (preparation == null) {
            throw new MediaPreparationException("Preparação não encontrada ou expirada");
        }
        return preparation.snapshot();
    }

    public PreparedMedia open(UUID id) {
        var preparation = preparations.get(id);
        if (preparation == null) {
            throw new MediaPreparationException("Preparação não encontrada ou expirada");
        }
        if (preparation.status() != PreparationStatus.READY) {
            throw new MediaNotReadyException("A mídia ainda está sendo preparada");
        }
        try {
            return preparation.mode() == PreparationMode.PROGRESSIVE
                    ? PreparedMedia.progressive(preparation.directMedia())
                    : PreparedMedia.muxed(cache.acquire(id));
        } catch (IllegalArgumentException exception) {
            removePreparation(preparation);
            throw new MediaPreparationException("Preparação não encontrada ou expirada", exception);
        }
    }

    @Scheduled(fixedDelayString = "${app.youtube.cleanup-interval:5m}")
    public void cleanup() {
        cache.cleanup();
        var now = Instant.now();
        preparations.values().forEach(preparation -> {
            if (preparation.status() == PreparationStatus.FAILED
                    && terminalAt.getOrDefault(preparation.id(), now).plus(cache.failedTtl()).isBefore(now)) {
                removePreparation(preparation);
            } else if (preparation.status() == PreparationStatus.READY
                    && preparation.mode() == PreparationMode.MUXED
                    && !cache.contains(preparation.id())) {
                removePreparation(preparation);
            }
        });
    }

    private void prepare(YoutubePreparation preparation, ResolvedYoutubeMedia media, RequestLimiter.Lease lease) {
        YoutubeMediaCache.CacheWorkspace workspace = null;
        try {
            workspace = cache.allocate(media.videoId(), preparation.id());
            downloader.download(media.videoStream(), workspace.videoPart(), maxInputBytes);
            downloader.download(media.audioStream(), workspace.audioPart(), maxInputBytes);
            var profile = copyCompatible(media) ? MuxProfile.COPY : MuxProfile.TRANSCODE;
            if (profile == MuxProfile.TRANSCODE
                    && (!ffmpegCapabilities.supportsH264() || !ffmpegCapabilities.supportsAac())) {
                throw new FfmpegUnavailableException("FFmpeg não possui os encoders H.264/AAC necessários");
            }
            ffmpegRunner.mux(workspace.videoPart(), workspace.audioPart(), workspace.outputPart(), profile, timeout);
            if (!Files.isRegularFile(workspace.outputPart()) || Files.size(workspace.outputPart()) == 0) {
                throw new MediaPreparationException("FFmpeg não produziu um arquivo de mídia válido");
            }
            var contentLength = Files.size(workspace.outputPart());
            var cached = cache.publish(workspace, "video/mp4", contentLength);
            preparation.ready(cached);
            terminalAt.put(preparation.id(), Instant.now());
        } catch (Exception exception) {
            if (workspace != null) {
                cache.discard(workspace);
            }
            preparation.failed(sanitize(exception));
            terminalAt.put(preparation.id(), Instant.now());
        } finally {
            lease.close();
            preparationSlots.release();
        }
    }

    private boolean copyCompatible(ResolvedYoutubeMedia media) {
        var video = media.videoStream();
        var audio = media.audioStream();
        var videoCodec = lower(video.videoCodec());
        var audioCodec = lower(audio.audioCodec());
        return "video/mp4".equals(mediaType(video))
                && "audio/mp4".equals(mediaType(audio))
                && (videoCodec.isBlank() || videoCodec.contains("avc1") || videoCodec.contains("avc3"))
                && (audioCodec.isBlank() || audioCodec.contains("mp4a") || audioCodec.contains("aac"));
    }

    private String sanitize(Exception exception) {
        if (exception instanceof FfmpegUnavailableException) {
            return "FFmpeg não está disponível para preparar este vídeo.";
        }
        if (exception instanceof MediaTooLargeException) {
            return "A mídia excede os limites configurados.";
        }
        return "Não foi possível preparar o vídeo do YouTube.";
    }

    private String mediaType(com.karaokestudio.youtube.YoutubeStream stream) {
        var contentType = stream.contentType();
        if (contentType == null) return "";
        var separator = contentType.indexOf(';');
        return lower((separator < 0 ? contentType : contentType.substring(0, separator)).trim());
    }

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT);
    }

    private static long positive(long value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " deve ser positivo");
        return value;
    }

    private void removePreparation(YoutubePreparation preparation) {
        preparations.remove(preparation.id(), preparation);
        byVideoId.remove(preparation.videoId(), preparation.id());
        terminalAt.remove(preparation.id());
    }
}
