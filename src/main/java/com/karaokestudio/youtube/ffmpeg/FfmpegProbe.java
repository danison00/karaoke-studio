package com.karaokestudio.youtube.ffmpeg;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

public class FfmpegProbe {

    private final String executable;
    private final CommandExecutor executor;

    public FfmpegProbe(String executable, CommandExecutor executor) {
        this.executable = executable;
        this.executor = executor;
    }

    public FfmpegCapabilities probe() {
        try {
            var versionResult = executor.execute(List.of(executable, "-version"), Duration.ofSeconds(10));
            if (versionResult.exitCode() != 0) {
                return unavailable(versionResult.stderr());
            }
            var encoderResult = executor.execute(List.of(executable, "-encoders"), Duration.ofSeconds(10));
            if (encoderResult.exitCode() != 0) {
                return unavailable(encoderResult.stderr());
            }
            var encoders = (encoderResult.stdout() + "\n" + encoderResult.stderr()).toLowerCase(Locale.ROOT);
            return new FfmpegCapabilities(
                    true,
                    encoders.contains("libx264"),
                    encoders.matches("(?s).*\\baac\\b.*"),
                    firstLine(versionResult.stdout()),
                    bounded(versionResult.stderr() + encoderResult.stderr()));
        } catch (FfmpegExecutionException exception) {
            return unavailable(exception.getMessage());
        }
    }

    private FfmpegCapabilities unavailable(String diagnostics) {
        return new FfmpegCapabilities(false, false, false, null, bounded(diagnostics));
    }

    private String firstLine(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.lines().findFirst().orElse(null);
    }

    private String bounded(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 4096 ? value : value.substring(0, 4096);
    }
}
