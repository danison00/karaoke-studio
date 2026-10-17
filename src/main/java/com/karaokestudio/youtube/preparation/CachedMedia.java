package com.karaokestudio.youtube.preparation;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class CachedMedia {

    private final UUID preparationId;
    private final String videoId;
    private final String contentType;
    private final long contentLength;
    private final Path file;
    private final Instant createdAt;
    private final AtomicInteger activeUsers = new AtomicInteger();
    private volatile Instant lastAccess;

    CachedMedia(UUID preparationId, String videoId, String contentType, long contentLength, Path file, Instant now) {
        this.preparationId = preparationId;
        this.videoId = videoId;
        this.contentType = contentType;
        this.contentLength = contentLength;
        this.file = file;
        this.createdAt = now;
        this.lastAccess = now;
    }

    public UUID preparationId() { return preparationId; }
    public String videoId() { return videoId; }
    public String contentType() { return contentType; }
    public long contentLength() { return contentLength; }
    public Path file() { return file; }
    public Instant createdAt() { return createdAt; }
    public Instant lastAccess() { return lastAccess; }
    public int activeUsers() { return activeUsers.get(); }

    void acquire(Instant now) {
        activeUsers.incrementAndGet();
        lastAccess = now;
    }

    void release(Instant now) {
        activeUsers.updateAndGet(value -> Math.max(0, value - 1));
        lastAccess = now;
    }
}
