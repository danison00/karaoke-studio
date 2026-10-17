package com.karaokestudio.youtube.preparation;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.karaokestudio.web.RequestLimiter;
import com.karaokestudio.youtube.JavaTubeResolver;
import com.karaokestudio.youtube.ResolvedYoutubeMedia;
import com.karaokestudio.youtube.UpstreamStreamClient;
import com.karaokestudio.youtube.YoutubeStream;
import com.karaokestudio.youtube.YoutubeUrlValidator;
import com.karaokestudio.youtube.YoutubeMediaService;
import com.karaokestudio.youtube.ffmpeg.CommandResult;
import com.karaokestudio.youtube.ffmpeg.FfmpegCapabilities;
import com.karaokestudio.youtube.ffmpeg.FfmpegProcess;
import com.karaokestudio.youtube.ffmpeg.FfmpegRunner;

class HlsPlaybackSessionServiceTest {

    @Test
    void startsAdaptiveSessionWithoutWaitingForFullDownload() {
        var service = service(media(ResolvedYoutubeMedia.Mode.ADAPTIVE));

        var snapshot = service.start("https://www.youtube.com/watch?v=abcdefghijk", "127.0.0.1");

        assertThat(snapshot.id()).isNotNull();
        assertThat(snapshot.mode()).isEqualTo(PreparationMode.HLS);
        assertThat(snapshot.status()).withFailMessage(snapshot::toString)
                .isIn(PlaybackStatus.BUFFERING, PlaybackStatus.STREAMING, PlaybackStatus.READY);
    }

    @Test
    void retainsProgressiveModeForSimpleStreams() {
        var service = service(media(ResolvedYoutubeMedia.Mode.PROGRESSIVE));

        var snapshot = service.start("https://www.youtube.com/watch?v=abcdefghijk", "127.0.0.1");

        assertThat(snapshot.mode()).isEqualTo(PreparationMode.PROGRESSIVE);
        assertThat(snapshot.status()).isEqualTo(PlaybackStatus.READY);
        assertThat(snapshot.mediaUrl()).contains("/media");
    }

    @Test
    void preservesFfmpegDiagnosticsWhenItStopsBeforePublishingAPlaylist() {
        var service = service(media(ResolvedYoutubeMedia.Mode.ADAPTIVE), new NoOutputRunner());

        var snapshot = service.start("https://www.youtube.com/watch?v=abcdefghijk", "127.0.0.1");
        snapshot = awaitTerminalState(service, snapshot.id());

        assertThat(snapshot.status()).isEqualTo(PlaybackStatus.FAILED);
        assertThat(snapshot.error())
                .contains("FFmpeg encerrou com código 7")
                .contains("relay returned 403");
    }

    private static HlsPlaybackSessionService service(ResolvedYoutubeMedia media) {
        return service(media, new FakeRunner());
    }

    private static HlsPlaybackSessionService service(ResolvedYoutubeMedia media, FfmpegRunner runner) {
        var resolver = new JavaTubeResolver(url -> new com.karaokestudio.youtube.JavaTubeClient() {
            @Override public com.karaokestudio.youtube.JavaTubeStreamQuery streams() { return new com.karaokestudio.youtube.JavaTubeStreamQuery() {
                @Override public YoutubeStream defaultResolution() { return media.mode() == ResolvedYoutubeMedia.Mode.PROGRESSIVE ? media.videoStream() : null; }
                @Override public YoutubeStream onlyAudio() { return media.audioStream(); }
                @Override public List<YoutubeStream> all() { return List.of(media.videoStream(), media.audioStream()); }
            }; }
            @Override public String title() { return media.title(); }
            @Override public long durationSeconds() { return media.durationMs() / 1000; }
        });
        var hlsCache = new HlsMediaCache(PathSupport.temp());
        var relay = new YoutubeRelayRegistry(new NoopUpstream(), URI.create("http://127.0.0.1:8080"), Duration.ofMinutes(2));
        return new HlsPlaybackSessionService(
                new YoutubeUrlValidator(2048), resolver,
                new RequestLimiter(10, 10, 10), runner,
                new FfmpegCapabilities(true, true, true, "test", ""), Runnable::run,
                hlsCache, new HlsPlaylistReader(), relay, null,
                Duration.ofMinutes(1), 1_000_000_000L, 3_600_000L);
    }

    private static ResolvedYoutubeMedia media(ResolvedYoutubeMedia.Mode mode) {
        return new ResolvedYoutubeMedia("abcdefghijk", "Título", mode, 120_000,
                stream("https://r1---sn.googlevideo.com/video", mode == ResolvedYoutubeMedia.Mode.PROGRESSIVE),
                mode == ResolvedYoutubeMedia.Mode.ADAPTIVE ? audioStream("https://r1---sn.googlevideo.com/audio") : null);
    }

    private static HlsPreparationSnapshot awaitTerminalState(HlsPlaybackSessionService service, java.util.UUID id) {
        for (var attempt = 0; attempt < 100; attempt++) {
            var snapshot = service.get(id);
            if (snapshot.status() == PlaybackStatus.FAILED || snapshot.status() == PlaybackStatus.READY
                    || snapshot.status() == PlaybackStatus.DEGRADED) {
                return snapshot;
            }
            try { Thread.sleep(10); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); }
        }
        return service.get(id);
    }

    private static YoutubeStream stream(String url, boolean progressive) {
        return new YoutubeStream() {
            @Override public URI upstreamUri() { return URI.create(url); }
            @Override public String contentType() { return "video/mp4"; }
            @Override public long contentLength() { return 100; }
            @Override public long durationMs() { return 120_000; }
            @Override public boolean progressive() { return progressive; }
            @Override public boolean sabr() { return false; }
            @Override public boolean otf() { return false; }
            @Override public void writeTo(java.io.OutputStream output) { }
        };
    }

    private static YoutubeStream audioStream(String url) {
        return new YoutubeStream() {
            @Override public URI upstreamUri() { return URI.create(url); }
            @Override public String contentType() { return "audio/mp4"; }
            @Override public long contentLength() { return 100; }
            @Override public long durationMs() { return 120_000; }
            @Override public boolean progressive() { return false; }
            @Override public boolean sabr() { return false; }
            @Override public boolean otf() { return false; }
            @Override public void writeTo(java.io.OutputStream output) { }
        };
    }

    private static final class FakeRunner implements FfmpegRunner {
        @Override public void mux(java.nio.file.Path video, java.nio.file.Path audio, java.nio.file.Path output,
                com.karaokestudio.youtube.ffmpeg.MuxProfile profile, Duration timeout) { }
        @Override public FfmpegProcess startHls(URI video, URI audio, java.nio.file.Path directory,
                com.karaokestudio.youtube.ffmpeg.FfmpegHlsProfile profile, long positionMs, Duration timeout) {
            try {
                java.nio.file.Files.writeString(directory.resolve("init.mp4"), "init");
                java.nio.file.Files.writeString(directory.resolve("segment-000000.m4s"), "segment");
                java.nio.file.Files.writeString(directory.resolve("index.m3u8"),
                        "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:2.0,\nsegment-000000.m4s\n#EXT-X-ENDLIST\n");
            } catch (java.io.IOException exception) { throw new IllegalStateException(exception); }
            return new FfmpegProcess() {
                @Override public boolean isAlive() { return false; }
                @Override public void cancel() { }
                @Override public java.util.concurrent.CompletionStage<CommandResult> completion() {
                    return java.util.concurrent.CompletableFuture.completedFuture(new CommandResult(0, "", ""));
                }
            };
        }
    }

    private static final class NoOutputRunner implements FfmpegRunner {
        @Override public void mux(java.nio.file.Path video, java.nio.file.Path audio, java.nio.file.Path output,
                com.karaokestudio.youtube.ffmpeg.MuxProfile profile, Duration timeout) { }

        @Override public FfmpegProcess startHls(URI video, URI audio, java.nio.file.Path directory,
                com.karaokestudio.youtube.ffmpeg.FfmpegHlsProfile profile, long positionMs, Duration timeout) {
            return new FfmpegProcess() {
                @Override public boolean isAlive() { return false; }
                @Override public void cancel() { }
                @Override public java.util.concurrent.CompletionStage<CommandResult> completion() {
                    return java.util.concurrent.CompletableFuture.completedFuture(
                            new CommandResult(7, "", "relay returned 403"));
                }
            };
        }
    }

    private static final class NoopUpstream implements UpstreamStreamClient {
        @Override public UpstreamResponse open(URI uri, String range) {
            return new UpstreamResponse(200, "video/mp4", 0, null, true, java.io.InputStream.nullInputStream(), () -> { });
        }
    }

    private static final class PathSupport {
        private static java.nio.file.Path temp() {
            try { return java.nio.file.Files.createTempDirectory("hls-service-test"); }
            catch (java.io.IOException exception) { throw new IllegalStateException(exception); }
        }
    }
}
