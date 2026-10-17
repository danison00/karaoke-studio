package com.karaokestudio.youtube.ffmpeg;

public record FfmpegCapabilities(
        boolean available,
        boolean supportsH264,
        boolean supportsAac,
        String version,
        String diagnostics) {
}
