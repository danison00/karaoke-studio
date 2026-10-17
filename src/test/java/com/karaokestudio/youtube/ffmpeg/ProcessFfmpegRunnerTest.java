package com.karaokestudio.youtube.ffmpeg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class ProcessFfmpegRunnerTest {

    @Test
    void buildsCopyCommandWithExplicitMapsAndFastStart() {
        var executor = new CapturingExecutor(new CommandResult(0, "", ""));
        var runner = new ProcessFfmpegRunner("ffmpeg", executor);

        runner.mux(Path.of("video.part"), Path.of("audio.part"), Path.of("output.part.mp4"), MuxProfile.COPY, Duration.ofSeconds(30));

        assertThat(executor.command).containsExactly(
                "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                "-i", "video.part", "-i", "audio.part",
                "-map", "0:v:0", "-map", "1:a:0",
                "-c:v", "copy", "-c:a", "copy", "-movflags", "+faststart", "-f", "mp4", "output.part.mp4");
    }

    @Test
    void buildsTranscodeCommandWithBrowserCompatibleCodecs() {
        var executor = new CapturingExecutor(new CommandResult(0, "", ""));
        var runner = new ProcessFfmpegRunner("ffmpeg", executor);

        runner.mux(Path.of("video.part"), Path.of("audio.part"), Path.of("output.part.mp4"), MuxProfile.TRANSCODE, Duration.ofSeconds(30));

        assertThat(executor.command).containsSubsequence(
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "160k", "-movflags", "+faststart", "-f", "mp4");
    }

    @Test
    void mapsCommandFailureAndTimeoutToStableExceptions() {
        var failed = new ProcessFfmpegRunner("ffmpeg", new CapturingExecutor(new CommandResult(1, "", "encoder failure")));
        assertThatThrownBy(() -> failed.mux(Path.of("video"), Path.of("audio"), Path.of("output"), MuxProfile.COPY, Duration.ofSeconds(1)))
                .isInstanceOf(FfmpegExecutionException.class)
                .hasMessageContaining("FFmpeg encerrou com código 1");

        var timedOut = new ProcessFfmpegRunner("ffmpeg", (command, timeout) -> {
            throw new FfmpegTimeoutException("timeout");
        });
        assertThatThrownBy(() -> timedOut.mux(Path.of("video"), Path.of("audio"), Path.of("output"), MuxProfile.COPY, Duration.ofSeconds(1)))
                .isInstanceOf(FfmpegTimeoutException.class);
    }

    @Test
    void probeReadsVersionAndEncoderCapabilities() {
        var executor = new CapturingExecutor(
                new CommandResult(0, "ffmpeg version 7.1", ""),
                new CommandResult(0, " V....D libx264\n A..... aac\n", ""));

        var capabilities = new FfmpegProbe("ffmpeg", executor).probe();

        assertThat(capabilities.available()).isTrue();
        assertThat(capabilities.supportsH264()).isTrue();
        assertThat(capabilities.supportsAac()).isTrue();
        assertThat(executor.commands).hasSize(2);
        assertThat(executor.commands.get(0)).containsExactly("ffmpeg", "-version");
        assertThat(executor.commands.get(1)).containsExactly("ffmpeg", "-encoders");
    }

    @Test
    void buildsStreamingCopyHlsCommandFromRelayUris() {
        var executor = new HlsCapturingExecutor();
        var runner = new ProcessFfmpegRunner("ffmpeg", executor);

        runner.startHls(
                URI.create("http://127.0.0.1/video"),
                URI.create("http://127.0.0.1/audio"),
                Path.of("generation"),
                FfmpegHlsProfile.COPY,
                0,
                Duration.ofMinutes(15));

        assertThat(executor.command).containsSubsequence(
                "-i", "http://127.0.0.1/video", "-i", "http://127.0.0.1/audio",
                "-c:v", "copy", "-c:a", "copy", "-f", "hls",
                "-hls_time", "2", "-hls_playlist_type", "event", "-hls_segment_type", "fmp4");
        assertThat(executor.workingDirectory).isEqualTo(Path.of("generation"));
        assertThat(executor.command.getLast()).isEqualTo("generation\\index.m3u8");
    }

    @Test
    void buildsAccurateSeekTranscodeHlsCommand() {
        var executor = new HlsCapturingExecutor();
        var runner = new ProcessFfmpegRunner("ffmpeg", executor);

        runner.startHls(
                URI.create("http://127.0.0.1/video"),
                URI.create("http://127.0.0.1/audio"),
                Path.of("generation"),
                FfmpegHlsProfile.TRANSCODE,
                120_000,
                Duration.ofMinutes(15));

        assertThat(executor.command).containsSubsequence(
                "-ss", "120.000", "-i", "http://127.0.0.1/video",
                "-ss", "120.000", "-i", "http://127.0.0.1/audio",
                "-vf", "scale=-2:360", "-c:v", "libx264", "-preset", "veryfast",
                "-force_key_frames", "expr:gte(t,n_forced*2)");
        assertThat(executor.command).containsSubsequence("-c:a", "aac", "-b:a", "128k");
    }

    private static final class CapturingExecutor implements CommandExecutor {
        private final List<List<String>> commands = new ArrayList<>();
        private final List<CommandResult> results;
        private List<String> command = List.of();

        private CapturingExecutor(CommandResult... results) {
            this.results = List.of(results);
        }

        @Override
        public CommandResult execute(List<String> command, Duration timeout) {
            this.command = List.copyOf(command);
            commands.add(this.command);
            return results.get(Math.min(commands.size() - 1, results.size() - 1));
        }
    }

    private static final class HlsCapturingExecutor implements CommandExecutor {
        private List<String> command = List.of();
        private Path workingDirectory;

        @Override
        public CommandResult execute(List<String> command, Duration timeout) {
            this.command = List.copyOf(command);
            return new CommandResult(0, "", "");
        }

        @Override
        public FfmpegProcess start(List<String> command, Duration timeout) {
            this.command = List.copyOf(command);
            return new FfmpegProcess() {
                @Override public boolean isAlive() { return false; }
                @Override public void cancel() { }
                @Override public java.util.concurrent.CompletionStage<CommandResult> completion() {
                    return java.util.concurrent.CompletableFuture.completedFuture(new CommandResult(0, "", ""));
                }
            };
        }

        @Override
        public FfmpegProcess start(List<String> command, Duration timeout, Path workingDirectory) {
            this.workingDirectory = workingDirectory;
            return start(command, timeout);
        }
    }
}
