package com.karaokestudio.youtube.preparation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class YoutubeMediaCacheTest {

    @TempDir
    Path tempDir;

    @Test
    void keepsAllWorkspaceFilesUnderRootAndPublishesAtomically() throws Exception {
        var cache = cache(Instant.parse("2026-01-01T00:00:00Z"));
        var workspace = cache.allocate("abcdefghijk", UUID.randomUUID());
        Files.writeString(workspace.outputPart(), "video");

        var media = cache.publish(workspace, "video/mp4", 5_000);

        assertThat(media.file()).startsWith(tempDir.toAbsolutePath());
        assertThat(media.file()).isRegularFile();
        assertThat(Files.readString(media.file())).isEqualTo("video");
        assertThat(workspace.outputPart()).doesNotExist();
    }

    @Test
    void doesNotEvictAnActiveFileButEvictsItAfterTtl() throws Exception {
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var cache = cache(clock);
        var id = UUID.randomUUID();
        var workspace = cache.allocate("abcdefghijk", id);
        Files.writeString(workspace.outputPart(), "video");
        var media = cache.publish(workspace, "video/mp4", 5);
        var lease = cache.acquire(id);

        clock.advance(Duration.ofHours(3));
        cache.cleanup();
        assertThat(media.file()).exists();

        lease.close();
        clock.advance(Duration.ofHours(3));
        cache.cleanup();
        assertThat(media.file()).doesNotExist();
    }

    @Test
    void removesAbandonedPartFilesOnCleanup() throws Exception {
        var cache = cache(Instant.parse("2026-01-01T00:00:00Z"));
        var abandoned = tempDir.resolve("abcdefghijk-dead.video.part");
        Files.writeString(abandoned, "partial");

        cache.cleanupAbandoned();

        assertThat(abandoned).doesNotExist();
    }

    private YoutubeMediaCache cache(Instant now) {
        return cache(new MutableClock(now));
    }

    private YoutubeMediaCache cache(Clock clock) {
        return new YoutubeMediaCache(tempDir, 100, Duration.ofHours(2), Duration.ofMinutes(10), clock);
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
    }
}
