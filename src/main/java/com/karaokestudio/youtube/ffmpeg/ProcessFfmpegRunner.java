package com.karaokestudio.youtube.ffmpeg;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProcessFfmpegRunner implements FfmpegRunner {

    private static final Logger log = LoggerFactory.getLogger(ProcessFfmpegRunner.class);

    private final String executable;
    private final CommandExecutor executor;

    public ProcessFfmpegRunner(String executable, CommandExecutor executor) {
        this.executable = Objects.requireNonNull(executable);
        this.executor = Objects.requireNonNull(executor);
    }

    @Override
    public void mux(Path video, Path audio, Path outputPart, MuxProfile profile, Duration timeout) {
        var command = new ArrayList<>(List.of(
                executable, "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                "-i", video.toString(), "-i", audio.toString(),
                "-map", "0:v:0", "-map", "1:a:0"));
        if (profile == MuxProfile.COPY) {
            command.addAll(List.of("-c:v", "copy", "-c:a", "copy"));
        } else {
            command.addAll(List.of(
                    "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p",
                    "-c:a", "aac", "-b:a", "160k"));
        }
        command.addAll(List.of("-movflags", "+faststart", "-f", "mp4", outputPart.toString()));

        var result = executor.execute(command, timeout);
        if (result.exitCode() != 0) {
            throw new FfmpegExecutionException(
                    "FFmpeg encerrou com código " + result.exitCode() + diagnostics(result.stderr()));
        }
    }

    @Override
    public FfmpegProcess startHls(
            URI videoRelay,
            URI audioRelay,
            Path generationDirectory,
            FfmpegHlsProfile profile,
            long positionMs,
            Duration timeout) {
        Objects.requireNonNull(videoRelay);
        Objects.requireNonNull(audioRelay);
        Objects.requireNonNull(generationDirectory);
        Objects.requireNonNull(profile);
        if (positionMs < 0) {
            throw new IllegalArgumentException("A posição inicial não pode ser negativa");
        }
        try {
            java.nio.file.Files.createDirectories(generationDirectory);
        } catch (IOException exception) {
            throw new FfmpegExecutionException("Não foi possível criar o diretório HLS", exception);
        }

        var command = new ArrayList<String>(List.of(
                executable, "-nostdin", "-hide_banner", "-loglevel", "warning", "-y"));
        if (profile == FfmpegHlsProfile.TRANSCODE && positionMs > 0) {
            var seek = formatSeconds(positionMs);
            command.addAll(List.of("-ss", seek));
        }
        command.addAll(List.of("-i", videoRelay.toString()));
        if (profile == FfmpegHlsProfile.TRANSCODE && positionMs > 0) {
            command.addAll(List.of("-ss", formatSeconds(positionMs)));
        }
        command.addAll(List.of("-i", audioRelay.toString(), "-map", "0:v:0", "-map", "1:a:0"));
        if (profile == FfmpegHlsProfile.COPY) {
            command.addAll(List.of("-c:v", "copy", "-c:a", "copy"));
        } else {
            command.addAll(List.of(
                    "-vf", "scale=-2:360",
                    "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p",
                    "-force_key_frames", "expr:gte(t,n_forced*2)",
                    "-c:a", "aac", "-b:a", "128k"));
        }
        command.addAll(List.of(
                "-f", "hls",
                "-hls_time", "2",
                "-hls_list_size", "0",
                "-hls_playlist_type", "event",
                "-hls_segment_type", "fmp4",
                "-hls_fmp4_init_filename", "init.mp4",
                "-hls_segment_filename", generationDirectory.resolve("segment-%06d.m4s").toString(),
                "-hls_flags", "temp_file+independent_segments",
                generationDirectory.resolve("index.m3u8").toString()));
        log.debug("youtube.ffmpeg.hls.command profile={} positionMs={} videoHost={} audioHost={} outputDirectory={}",
                profile, positionMs, videoRelay.getHost(), audioRelay.getHost(), generationDirectory);
        return executor.start(command, timeout, generationDirectory);
    }

    private String formatSeconds(long positionMs) {
        return String.format(java.util.Locale.ROOT, "%.3f", positionMs / 1_000d);
    }

    private String diagnostics(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return "";
        }
        return ": " + (stderr.length() <= 4096 ? stderr : stderr.substring(0, 4096));
    }
}
