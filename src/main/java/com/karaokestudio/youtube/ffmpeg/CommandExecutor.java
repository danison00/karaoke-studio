package com.karaokestudio.youtube.ffmpeg;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

@FunctionalInterface
public interface CommandExecutor {

    CommandResult execute(List<String> command, Duration timeout);

    default FfmpegProcess start(List<String> command, Duration timeout) {
        throw new UnsupportedOperationException("Este executor não suporta processos assíncronos");
    }

    default FfmpegProcess start(List<String> command, Duration timeout, Path workingDirectory) {
        return start(command, timeout);
    }
}
