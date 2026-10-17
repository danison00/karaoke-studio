package com.karaokestudio.youtube.preparation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public class YoutubeMediaCache {

    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    private final Path root;
    private final long maxBytes;
    private final Duration readyTtl;
    private final Duration failedTtl;
    private final Clock clock;
    private final Map<UUID, CachedMedia> entries = new HashMap<>();

    public YoutubeMediaCache(Path root, long maxBytes, Duration readyTtl, Duration failedTtl, Clock clock) {
        this.root = Objects.requireNonNull(root).toAbsolutePath().normalize();
        this.maxBytes = positive(maxBytes, "maxBytes");
        this.readyTtl = positive(readyTtl, "readyTtl");
        this.failedTtl = positive(failedTtl, "failedTtl");
        this.clock = Objects.requireNonNull(clock);
        try {
            Files.createDirectories(this.root);
        } catch (IOException exception) {
            throw new IllegalStateException("Não foi possível criar o diretório de cache", exception);
        }
    }

    public synchronized CacheWorkspace allocate(String videoId, UUID preparationId) {
        if (videoId == null || !VIDEO_ID.matcher(videoId).matches()) {
            throw new IllegalArgumentException("ID de vídeo inválido para cache");
        }
        Objects.requireNonNull(preparationId);
        return new CacheWorkspace(
                preparationId,
                videoId,
                child(preparationId + ".video.part"),
                child(preparationId + ".audio.part"),
                child(preparationId + ".output.part"),
                child(preparationId + ".mp4"));
    }

    public synchronized CachedMedia publish(CacheWorkspace workspace, String contentType, long contentLength) {
        Objects.requireNonNull(workspace);
        if (!workspace.outputPart().toAbsolutePath().normalize().startsWith(root)
                || !Files.isRegularFile(workspace.outputPart())) {
            throw new IllegalArgumentException("Arquivo de saída do cache inválido");
        }
        try {
            moveAtomically(workspace.outputPart(), workspace.outputFile());
            var media = new CachedMedia(
                    workspace.preparationId(), workspace.videoId(), contentType, contentLength,
                    workspace.outputFile(), clock.instant());
            entries.put(workspace.preparationId(), media);
            deleteQuietly(workspace.videoPart());
            deleteQuietly(workspace.audioPart());
            return media;
        } catch (IOException exception) {
            deleteQuietly(workspace.outputPart());
            throw new IllegalStateException("Não foi possível publicar mídia no cache", exception);
        }
    }

    public synchronized void discard(CacheWorkspace workspace) {
        if (workspace == null) {
            return;
        }
        deleteQuietly(workspace.videoPart());
        deleteQuietly(workspace.audioPart());
        deleteQuietly(workspace.outputPart());
        deleteQuietly(workspace.outputFile());
    }

    public synchronized CacheLease acquire(UUID preparationId) {
        var media = entries.get(preparationId);
        if (media == null || !Files.isRegularFile(media.file())) {
            throw new IllegalArgumentException("Mídia de cache não encontrada");
        }
        media.acquire(clock.instant());
        return new CacheLease(media);
    }

    public synchronized boolean contains(UUID preparationId) {
        var media = entries.get(preparationId);
        return media != null && Files.isRegularFile(media.file());
    }

    public synchronized void cleanupAbandoned() {
        try (var files = Files.list(root)) {
            files.filter(path -> path.getFileName().toString().contains(".part"))
                    .forEach(this::deleteQuietly);
        } catch (IOException ignored) {
            // Cleanup is best effort and must not interrupt playback.
        }
    }

    public synchronized void cleanup() {
        var now = clock.instant();
        entries.values().stream()
                .filter(media -> media.activeUsers() == 0)
                .filter(media -> media.lastAccess().plus(readyTtl).isBefore(now))
                .sorted(Comparator.comparing(CachedMedia::lastAccess))
                .toList()
                .forEach(this::evict);

        var total = entries.values().stream()
                .mapToLong(media -> media.activeUsers() == 0 ? Math.max(0, media.contentLength()) : 0)
                .sum();
        if (total > maxBytes) {
            entries.values().stream()
                    .filter(media -> media.activeUsers() == 0)
                    .sorted(Comparator.comparing(CachedMedia::lastAccess))
                    .toList()
                    .forEach(media -> {
                        if (currentSize() > maxBytes) {
                            evict(media);
                        }
                    });
        }
    }

    public synchronized long currentSize() {
        return entries.values().stream()
                .filter(media -> media.activeUsers() == 0)
                .mapToLong(media -> {
                    try { return Files.size(media.file()); } catch (IOException ignored) { return 0; }
                })
                .sum();
    }

    public Duration failedTtl() {
        return failedTtl;
    }

    private void evict(CachedMedia media) {
        if (media.activeUsers() > 0) {
            return;
        }
        entries.remove(media.preparationId());
        deleteQuietly(media.file());
    }

    private Path child(String name) {
        var path = root.resolve(name).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Caminho de cache fora da raiz");
        }
        return path;
    }

    private void moveAtomically(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AccessDeniedException exception) {
            // A few Windows sandbox/filesystem providers reject renaming a newly
            // created temporary file. Keep the destination private until copy
            // completes, then remove the temporary source.
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
            Files.delete(source);
        }
    }

    private void deleteQuietly(Path path) {
        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
    }

    private static long positive(long value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " deve ser positivo");
        return value;
    }

    private static Duration positive(Duration value, String name) {
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " deve ser positivo");
        return value;
    }

    public record CacheWorkspace(
            UUID preparationId,
            String videoId,
            Path videoPart,
            Path audioPart,
            Path outputPart,
            Path outputFile) {
    }

    public final class CacheLease implements AutoCloseable {
        private final CachedMedia media;
        private boolean closed;

        private CacheLease(CachedMedia media) {
            this.media = media;
        }

        public CachedMedia media() {
            return media;
        }

        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;
                synchronized (YoutubeMediaCache.this) {
                    media.release(clock.instant());
                }
            }
        }
    }
}
