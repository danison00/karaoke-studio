package com.karaokestudio.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.karaokestudio.youtube.RangeNotSatisfiableException;
import com.karaokestudio.youtube.preparation.PreparedMedia;
import com.karaokestudio.youtube.preparation.YoutubeMediaCache;

class CachedMediaStreamerTest {

    @TempDir
    Path tempDir;

    @Test
    void streamsWholeFileAndReleasesCacheLease() throws Exception {
        var cache = cache();
        var prepared = prepared(cache, "0123456789");

        var stream = new CachedMediaStreamer().open(prepared, null);

        assertThat(stream.status()).isEqualTo(200);
        assertThat(stream.contentLength()).isEqualTo(10);
        assertThat(stream.contentRange()).isNull();
        assertThat(stream.body().readAllBytes()).isEqualTo("0123456789".getBytes());
        stream.close();
        assertThat(cache.currentSize()).isEqualTo(10);
    }

    @Test
    void supportsClosedOpenEndedAndSuffixRanges() throws Exception {
        var streamer = new CachedMediaStreamer();

        try (var closed = streamer.open(prepared(cache(), "0123456789"), "bytes=2-5")) {
            assertThat(closed.status()).isEqualTo(206);
            assertThat(closed.contentRange()).isEqualTo("bytes 2-5/10");
            assertThat(new String(closed.body().readAllBytes())).isEqualTo("2345");
        }
        try (var open = streamer.open(prepared(cache(), "0123456789"), "bytes=7-")) {
            assertThat(open.contentRange()).isEqualTo("bytes 7-9/10");
            assertThat(new String(open.body().readAllBytes())).isEqualTo("789");
        }
        try (var suffix = streamer.open(prepared(cache(), "0123456789"), "bytes=-4")) {
            assertThat(suffix.contentRange()).isEqualTo("bytes 6-9/10");
            assertThat(new String(suffix.body().readAllBytes())).isEqualTo("6789");
        }
    }

    @Test
    void rejectsInvalidAndMultiRanges() throws Exception {
        var streamer = new CachedMediaStreamer();

        assertThatThrownBy(() -> streamer.open(prepared(cache(), "0123456789"), "bytes=10-20"))
                .isInstanceOf(RangeNotSatisfiableException.class);
        assertThatThrownBy(() -> streamer.open(prepared(cache(), "0123456789"), "bytes=0-1,2-3"))
                .isInstanceOf(RangeNotSatisfiableException.class);
    }

    private YoutubeMediaCache cache() {
        return new YoutubeMediaCache(tempDir.resolve(UUID.randomUUID().toString()), 10_000,
                Duration.ofHours(2), Duration.ofMinutes(10), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    }

    private PreparedMedia prepared(YoutubeMediaCache cache, String content) throws Exception {
        var id = UUID.randomUUID();
        var workspace = cache.allocate("abcdefghijk", id);
        Files.writeString(workspace.outputPart(), content);
        cache.publish(workspace, "video/mp4", content.length());
        return PreparedMedia.muxed(cache.acquire(id));
    }
}
