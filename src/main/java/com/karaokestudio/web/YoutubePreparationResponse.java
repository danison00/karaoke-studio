package com.karaokestudio.web;

import java.util.UUID;
import java.util.List;

import com.karaokestudio.youtube.preparation.HlsPreparationSnapshot;
import com.karaokestudio.youtube.preparation.HlsRange;
import com.karaokestudio.youtube.preparation.YoutubePreparationSnapshot;

public record YoutubePreparationResponse(
        UUID id,
        String videoId,
        String title,
        String status,
        String mode,
        String contentType,
        long durationMs,
        Long contentLength,
        String mediaUrl,
        String error,
        Long playbackGeneration,
        Long producerGeneration,
        Long acceptedSeekSequence,
        Long requestedPositionMs,
        Long timelineOffsetMs,
        String playlistUrl,
        List<HlsRange> availableRanges,
        String recoverableError) {

    public YoutubePreparationResponse(
            UUID id,
            String videoId,
            String title,
            String status,
            String mode,
            String contentType,
            long durationMs,
            Long contentLength,
            String mediaUrl,
            String error) {
        this(id, videoId, title, status, mode, contentType, durationMs, contentLength, mediaUrl, error,
                null, null, null, null, null, null, List.of(), null);
    }

    public static YoutubePreparationResponse from(YoutubePreparationSnapshot snapshot) {
        return new YoutubePreparationResponse(
                snapshot.id(),
                snapshot.videoId(),
                snapshot.title(),
                snapshot.status().name(),
                snapshot.mode().name(),
                snapshot.contentType(),
                snapshot.durationMs(),
                snapshot.contentLength(),
                snapshot.mediaUrl(),
                snapshot.error());
    }

    public static YoutubePreparationResponse from(HlsPreparationSnapshot snapshot) {
        return new YoutubePreparationResponse(
                snapshot.id(),
                snapshot.videoId(),
                snapshot.title(),
                snapshot.status().name(),
                snapshot.mode().name(),
                snapshot.mode().name().equals("HLS") ? "application/vnd.apple.mpegurl" : "video/mp4",
                snapshot.durationMs(),
                null,
                snapshot.mediaUrl(),
                snapshot.error(),
                snapshot.playbackGeneration(),
                snapshot.producerGeneration(),
                snapshot.acceptedSeekSequence(),
                snapshot.requestedPositionMs(),
                snapshot.timelineOffsetMs(),
                snapshot.playlistUrl(),
                snapshot.availableRanges(),
                snapshot.recoverableError());
    }
}
