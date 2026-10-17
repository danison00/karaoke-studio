package com.karaokestudio.youtube.preparation;

import java.util.List;
import java.util.UUID;

public record HlsPreparationSnapshot(
        UUID id,
        String videoId,
        String title,
        PlaybackStatus status,
        PreparationMode mode,
        long durationMs,
        long playbackGeneration,
        Long producerGeneration,
        long acceptedSeekSequence,
        long requestedPositionMs,
        long timelineOffsetMs,
        String playlistUrl,
        List<HlsRange> availableRanges,
        String mediaUrl,
        String error,
        String recoverableError) {

    public HlsPreparationSnapshot {
        availableRanges = availableRanges == null ? List.of() : List.copyOf(availableRanges);
        if (durationMs < 0 || playbackGeneration < 0 || acceptedSeekSequence < 0
                || requestedPositionMs < 0 || timelineOffsetMs < 0) {
            throw new IllegalArgumentException("Snapshot de preparação inválido");
        }
        if (producerGeneration != null && producerGeneration < 0) {
            throw new IllegalArgumentException("Geração produtora inválida");
        }
    }
}
