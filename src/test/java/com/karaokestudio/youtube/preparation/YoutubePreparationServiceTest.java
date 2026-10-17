package com.karaokestudio.youtube.preparation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.karaokestudio.web.RequestLimiter;
import com.karaokestudio.youtube.AdaptiveStreamSelector;
import com.karaokestudio.youtube.JavaTubeClient;
import com.karaokestudio.youtube.JavaTubeResolver;
import com.karaokestudio.youtube.JavaTubeStreamQuery;
import com.karaokestudio.youtube.ResolvedYoutubeMedia;
import com.karaokestudio.youtube.YoutubeStream;
import com.karaokestudio.youtube.ffmpeg.FfmpegCapabilities;
import com.karaokestudio.youtube.ffmpeg.FfmpegRunner;
import com.karaokestudio.youtube.ffmpeg.MuxProfile;
import com.karaokestudio.youtube.YoutubeUrlValidator;

class YoutubePreparationServiceTest {

    private static final String URL = "https://www.youtube.com/watch?v=abcdefghijk";

    @TempDir
    Path tempDir;

    @Test
    void progressiveMediaIsReadyWithoutFfmpeg() {
        var runner = mock(FfmpegRunner.class);
        var resolved = progressiveMedia();
        var service = service(resolver(resolved), runner, Runnable::run);

        var snapshot = service.start(URL, "127.0.0.1");

        assertThat(snapshot.status()).isEqualTo(PreparationStatus.READY);
        assertThat(snapshot.mode()).isEqualTo(PreparationMode.PROGRESSIVE);
        assertThat(snapshot.mediaUrl()).isEqualTo("/api/youtube/preparations/" + snapshot.id() + "/media");
        verify(runner, never()).mux(any(), any(), any(), any(), any());
    }

    @Test
    void adaptiveMediaDownloadsAndPublishesMuxedFile() throws Exception {
        var runner = (FfmpegRunner) (video, audio, output, profile, timeout) -> {
            try {
                Files.writeString(output, "muxed");
            } catch (java.io.IOException exception) {
                throw new RuntimeException(exception);
            }
        };
        var downloader = mock(SecureStreamDownloader.class);
        when(downloader.download(any(), any(), anyLong())).thenReturn(10L);
        var service = service(resolver(adaptiveMedia()), runner, Runnable::run, downloader);

        var snapshot = service.start(URL, "127.0.0.1");

        assertThat(snapshot.status()).isEqualTo(PreparationStatus.READY);
        assertThat(snapshot.mode()).isEqualTo(PreparationMode.MUXED);
        assertThat(snapshot.contentType()).isEqualTo("video/mp4");
        try (var media = service.open(snapshot.id())) {
            assertThat(Files.readString(media.file())).isEqualTo("muxed");
        }
    }

    @Test
    void concurrentStartsForSameVideoShareOnePreparationIdAndJob() throws Exception {
        var tasks = new QueueExecutor();
        var runner = mock(FfmpegRunner.class);
        var service = service(resolver(adaptiveMedia()), runner, tasks);

        try (var callers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var firstFuture = callers.submit(() -> service.start(URL, "10.0.0.1"));
            var secondFuture = callers.submit(() -> service.start(URL, "10.0.0.2"));
            var first = firstFuture.get();
            var second = secondFuture.get();

            assertThat(first.id()).isEqualTo(second.id());
            assertThat(first.status()).isEqualTo(PreparationStatus.PREPARING);
            assertThat(tasks.tasks).hasSize(1);
        }
    }

    @Test
    void rejectsAdaptivePreparationWhenFfmpegIsUnavailable() {
        var service = service(resolver(adaptiveMedia()), mock(FfmpegRunner.class), Runnable::run,
                mock(SecureStreamDownloader.class), new FfmpegCapabilities(false, false, false, null, "missing"));

        assertThatThrownBy(() -> service.start(URL, "127.0.0.1"))
                .isInstanceOf(com.karaokestudio.youtube.ffmpeg.FfmpegUnavailableException.class)
                .hasMessageContaining("FFmpeg");
    }

    private YoutubePreparationService service(JavaTubeResolver resolver, FfmpegRunner runner, java.util.concurrent.Executor executor) {
        return service(resolver, runner, executor, mock(SecureStreamDownloader.class),
                new FfmpegCapabilities(true, true, true, "ffmpeg 7", ""));
    }

    private YoutubePreparationService service(JavaTubeResolver resolver, FfmpegRunner runner,
            java.util.concurrent.Executor executor, SecureStreamDownloader downloader) {
        return service(resolver, runner, executor, downloader,
                new FfmpegCapabilities(true, true, true, "ffmpeg 7", ""));
    }

    private YoutubePreparationService service(JavaTubeResolver resolver, FfmpegRunner runner,
            java.util.concurrent.Executor executor, SecureStreamDownloader downloader, FfmpegCapabilities capabilities) {
        var cache = new YoutubeMediaCache(tempDir, 10_000, Duration.ofHours(2), Duration.ofMinutes(10), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        return new YoutubePreparationService(
                new YoutubeUrlValidator(2048), resolver, new RequestLimiter(8, 2, 10), downloader, cache,
                runner, capabilities, executor, 2, 8, Duration.ofMinutes(15), 2_000_000, 7_200_000);
    }

    private static JavaTubeResolver resolver(ResolvedYoutubeMedia media) {
        return new JavaTubeResolver(url -> new JavaTubeClient() {
            @Override public String title() { return media.title(); }
            @Override public long durationSeconds() { return media.durationMs() / 1_000; }
            @Override public JavaTubeStreamQuery streams() {
                return new JavaTubeStreamQuery() {
                    @Override public YoutubeStream defaultResolution() { return media.mode() == ResolvedYoutubeMedia.Mode.PROGRESSIVE ? media.videoStream() : null; }
                    @Override public YoutubeStream onlyAudio() { return media.audioStream(); }
                    @Override public List<YoutubeStream> all() {
                        return java.util.stream.Stream.of(media.videoStream(), media.audioStream()).filter(java.util.Objects::nonNull).toList();
                    }
                };
            }
        }, new AdaptiveStreamSelector());
    }

    private static ResolvedYoutubeMedia progressiveMedia() {
        return new ResolvedYoutubeMedia("abcdefghijk", "Vídeo direto", ResolvedYoutubeMedia.Mode.PROGRESSIVE,
                60_000, stream("progressive", "video/mp4", true, true, false), null);
    }

    private static ResolvedYoutubeMedia adaptiveMedia() {
        return new ResolvedYoutubeMedia("abcdefghijk", "Vídeo adaptativo", ResolvedYoutubeMedia.Mode.ADAPTIVE,
                60_000, stream("video", "video/mp4", false, true, false), stream("audio", "audio/mp4", false, false, true));
    }

    private static YoutubeStream stream(String path, String contentType, boolean progressive, boolean video, boolean audio) {
        return new YoutubeStream() {
            @Override public URI upstreamUri() { return URI.create("https://media.example/" + path); }
            @Override public String contentType() { return contentType; }
            @Override public long contentLength() { return 10; }
            @Override public long durationMs() { return 60_000; }
            @Override public boolean progressive() { return progressive; }
            @Override public boolean sabr() { return false; }
            @Override public boolean otf() { return false; }
            @Override public boolean includesVideo() { return video; }
            @Override public boolean includesAudio() { return audio; }
            @Override public String videoCodec() { return video ? "avc1.64001f" : null; }
            @Override public String audioCodec() { return audio ? "mp4a.40.2" : null; }
            @Override public String resolution() { return video ? "720p" : null; }
            @Override public long bitrate() { return video ? 1_000 : 128; }
            @Override public void writeTo(OutputStream output) { }
        };
    }

    private static final class QueueExecutor implements java.util.concurrent.Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable command) { tasks.add(command); }
    }
}
