package com.karaokestudio.youtube.ffmpeg;

public record CommandResult(int exitCode, String stdout, String stderr) {
}
