package com.karaokestudio.youtube.preparation;

import java.util.UUID;

import com.karaokestudio.youtube.ResolvedYoutubeMedia;

final class YoutubePreparation {

    private final UUID id;
    private final String videoId;
    private final String title;
    private final PreparationMode mode;
    private final long durationMs;
    private final ResolvedYoutubeMedia directMedia;
    private volatile PreparationStatus status;
    private volatile CachedMedia cachedMedia;
    private volatile String error;

    YoutubePreparation(UUID id, ResolvedYoutubeMedia media) {
        this.id = id;
        this.videoId = media.videoId();
        this.title = media.title();
        this.mode = media.mode() == ResolvedYoutubeMedia.Mode.PROGRESSIVE
                ? PreparationMode.PROGRESSIVE : PreparationMode.MUXED;
        this.durationMs = media.durationMs();
        this.directMedia = media.mode() == ResolvedYoutubeMedia.Mode.PROGRESSIVE ? media : null;
        this.status = PreparationStatus.PREPARING;
    }

    UUID id() { return id; }
    String videoId() { return videoId; }
    PreparationMode mode() { return mode; }
    ResolvedYoutubeMedia directMedia() { return directMedia; }
    CachedMedia cachedMedia() { return cachedMedia; }
    PreparationStatus status() { return status; }

    void ready(CachedMedia cachedMedia) {
        this.cachedMedia = cachedMedia;
        this.status = PreparationStatus.READY;
    }

    void ready() {
        this.status = PreparationStatus.READY;
    }

    void failed(String error) {
        this.error = error;
        this.status = PreparationStatus.FAILED;
    }

    YoutubePreparationSnapshot snapshot() {
        var contentType = mode == PreparationMode.PROGRESSIVE
                ? directMedia.contentType() : "video/mp4";
        Long contentLength = mode == PreparationMode.PROGRESSIVE
                ? directMedia.contentLength() >= 0 ? directMedia.contentLength() : null
                : cachedMedia == null ? null : cachedMedia.contentLength();
        var mediaUrl = status == PreparationStatus.READY
                ? "/api/youtube/preparations/" + id + "/media" : null;
        return new YoutubePreparationSnapshot(
                id, videoId, title, status, mode, contentType, durationMs, contentLength, mediaUrl, error);
    }
}
