package com.karaokestudio.youtube.preparation;

import java.util.UUID;

public record YoutubePreparationSnapshot(
        UUID id,
        String videoId,
        String title,
        PreparationStatus status,
        PreparationMode mode,
        String contentType,
        long durationMs,
        Long contentLength,
        String mediaUrl,
        String error) {
}
