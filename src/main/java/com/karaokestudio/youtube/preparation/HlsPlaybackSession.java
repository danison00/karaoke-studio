package com.karaokestudio.youtube.preparation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.karaokestudio.youtube.ResolvedYoutubeMedia;
import com.karaokestudio.youtube.ffmpeg.FfmpegProcess;

final class HlsPlaybackSession {

    private final UUID id;
    private final String videoId;
    private String title;
    private long durationMs;
    private PreparationMode mode = PreparationMode.HLS;
    private PlaybackStatus status = PlaybackStatus.RESOLVING;
    private ResolvedYoutubeMedia media;
    private final Map<Long, HlsGenerationSnapshot> generations = new TreeMap<>();
    private long playbackGeneration;
    private Long producerGeneration;
    private long acceptedSeekSequence;
    private long requestedPositionMs;
    private long timelineOffsetMs;
    private String playlistUrl;
    private String error;
    private String recoverableError;
    private FfmpegProcess process;
    private boolean closed;

    HlsPlaybackSession(UUID id, String videoId, String title, long durationMs) {
        this.id = id;
        this.videoId = videoId;
        this.title = title == null || title.isBlank() ? videoId : title;
        this.durationMs = Math.max(0, durationMs);
    }

    synchronized void resolved(ResolvedYoutubeMedia resolved) {
        ensureOpen();
        this.media = resolved;
        this.title = resolved.title();
        this.durationMs = Math.max(0, resolved.durationMs());
        this.mode = resolved.mode() == ResolvedYoutubeMedia.Mode.PROGRESSIVE
                ? PreparationMode.PROGRESSIVE : PreparationMode.HLS;
    }

    synchronized void progressiveReady(ResolvedYoutubeMedia resolved) {
        resolved(resolved);
        status = PlaybackStatus.READY;
        requestedPositionMs = 0;
        playlistUrl = null;
    }

    synchronized void beginProducer(long generation, FfmpegProcess process, String playlist) {
        ensureOpen();
        this.producerGeneration = generation;
        this.process = process;
        this.playlistUrl = playlist;
        this.status = PlaybackStatus.BUFFERING;
        this.error = null;
    }

    synchronized void attachProcess(FfmpegProcess process) {
        ensureOpen();
        this.process = process;
    }

    synchronized UUID id() {
        return id;
    }

    synchronized void publish(HlsGenerationSnapshot generation) {
        ensureOpen();
        generations.put(generation.generation(), generation);
        if (generation.generation() == playbackGeneration) {
            playlistUrl = generation.playlistUrl();
            if (generation.playable()) status = generation.ended() ? PlaybackStatus.READY : PlaybackStatus.STREAMING;
        }
    }

    synchronized SeekDecision acceptSeek(long requestSequence, long positionMs) {
        ensureOpen();
        var bounded = Math.max(0, Math.min(positionMs, durationMs));
        if (requestSequence <= acceptedSeekSequence) {
            return new SeekDecision(false, null, null, bounded);
        }
        acceptedSeekSequence = requestSequence;
        requestedPositionMs = bounded;
        var cached = generations.values().stream()
                .filter(range -> range.playable() && bounded >= range.fromMs() && bounded < range.toMs())
                .max(Comparator.comparingLong(HlsGenerationSnapshot::generation))
                .orElse(null);
        if (cached != null) {
            playbackGeneration = cached.generation();
            timelineOffsetMs = cached.fromMs();
            playlistUrl = cached.playlistUrl();
            status = PlaybackStatus.STREAMING;
            return new SeekDecision(true, null, null, bounded);
        }
        var nextGeneration = generations.keySet().stream().mapToLong(Long::longValue).max().orElse(-1) + 1;
        var cancelled = producerGeneration;
        playbackGeneration = nextGeneration;
        timelineOffsetMs = bounded;
        playlistUrl = null;
        status = PlaybackStatus.BUFFERING;
        return new SeekDecision(true, nextGeneration, cancelled, bounded);
    }

    synchronized void producerFinished(long generation, boolean successful, String failure) {
        if (closed || producerGeneration == null || producerGeneration != generation) return;
        producerGeneration = null;
        process = null;
        var range = generations.get(generation);
        if (successful && range != null && (range.ended() || range.toMs() >= durationMs)) {
            status = PlaybackStatus.READY;
            return;
        }
        if ((range != null && range.playable()) || !generations.isEmpty()) {
            recoverableError = failure == null ? "A continuação do stream falhou" : failure;
            status = PlaybackStatus.DEGRADED;
        } else {
            error = failure == null
                    ? "Não foi possível iniciar o stream HLS (preparação " + id + ")"
                    : failure;
            status = PlaybackStatus.FAILED;
        }
    }

    synchronized void producerFailed(long generation, String failure) {
        producerFinished(generation, false, failure);
    }

    synchronized boolean isCurrentProducer(long generation) {
        return !closed && producerGeneration != null && producerGeneration == generation;
    }

    synchronized FfmpegProcess currentProcess() {
        return process;
    }

    synchronized void setError(String message) {
        if (!closed) {
            error = message;
            status = PlaybackStatus.FAILED;
        }
    }

    synchronized ResolvedYoutubeMedia media() {
        return media;
    }

    synchronized boolean isProgressive() {
        return mode == PreparationMode.PROGRESSIVE;
    }

    synchronized HlsPreparationSnapshot snapshot() {
        var ranges = generations.values().stream().map(HlsGenerationSnapshot::range).toList();
        return new HlsPreparationSnapshot(
                id, videoId, title, status, mode, durationMs,
                playbackGeneration, producerGeneration, acceptedSeekSequence,
                requestedPositionMs, timelineOffsetMs, playlistUrl, ranges,
                isProgressive() && status == PlaybackStatus.READY
                        ? "/api/youtube/preparations/" + id + "/media" : null,
                error, recoverableError);
    }

    synchronized void close() {
        closed = true;
        status = PlaybackStatus.CANCELLED;
        if (process != null) process.cancel();
        process = null;
        producerGeneration = null;
    }

    synchronized boolean closed() {
        return closed;
    }

    private void ensureOpen() {
        if (closed) throw new MediaPreparationException("A sessão de reprodução foi encerrada");
    }

    record SeekDecision(boolean changed, Long startGeneration, Long cancelGeneration, long positionMs) { }
}
