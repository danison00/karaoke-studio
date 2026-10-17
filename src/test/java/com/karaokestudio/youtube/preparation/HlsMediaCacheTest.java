package com.karaokestudio.youtube.preparation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HlsMediaCacheTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesOnlyAllowlistedCompletedHlsResources() throws Exception {
        var sessionId = UUID.randomUUID();
        var cache = new HlsMediaCache(tempDir);
        var generation = cache.createGeneration(sessionId, 0);
        Files.writeString(generation.resolve("init.mp4"), "init");
        Files.writeString(generation.resolve("segment-000000.m4s"), "segment");

        assertThat(cache.resolveResource(sessionId, 0, "init.mp4")).contains(generation.resolve("init.mp4"));
        assertThat(cache.resolveResource(sessionId, 0, "segment-000000.m4s"))
                .contains(generation.resolve("segment-000000.m4s"));
        assertThat(cache.resolveResource(sessionId, 0, "segment-000000.m4s.tmp")).isEmpty();
        assertThat(cache.resolveResource(sessionId, 0, "../init.mp4")).isEmpty();
    }

    @Test
    void atomicallyPublishesTemporaryFile() throws Exception {
        var cache = new HlsMediaCache(tempDir);
        var generation = cache.createGeneration(UUID.randomUUID(), 1);
        var temporary = generation.resolve("segment-000000.m4s.tmp");
        var published = generation.resolve("segment-000000.m4s");
        Files.writeString(temporary, "complete");

        cache.publish(temporary, published);

        assertThat(Files.readString(published)).isEqualTo("complete");
        assertThat(Files.exists(temporary)).isFalse();
    }

    @Test
    void rejectsPathsOutsideCacheRoot() {
        var cache = new HlsMediaCache(tempDir);

        assertThatThrownBy(() -> cache.createGeneration(UUID.randomUUID(), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
