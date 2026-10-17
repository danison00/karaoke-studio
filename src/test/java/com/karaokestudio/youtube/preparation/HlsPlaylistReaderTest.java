package com.karaokestudio.youtube.preparation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HlsPlaylistReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void calculatesAvailableLogicalRangeFromPublishedExtinfEntries() throws Exception {
        Files.writeString(tempDir.resolve("init.mp4"), "init");
        Files.writeString(tempDir.resolve("segment-000000.m4s"), "one");
        Files.writeString(tempDir.resolve("segment-000001.m4s"), "two");
        Files.writeString(tempDir.resolve("index.m3u8"), """
                #EXTM3U
                #EXT-X-VERSION:7
                #EXT-X-MAP:URI="init.mp4"
                #EXTINF:2.5,
                segment-000000.m4s
                #EXTINF:3.25,
                segment-000001.m4s
                #EXT-X-ENDLIST
                """);

        var snapshot = new HlsPlaylistReader().inspect(
                tempDir, 4, 10_000, "/hls/4/index.m3u8").orElseThrow();

        assertThat(snapshot.fromMs()).isEqualTo(10_000);
        assertThat(snapshot.toMs()).isEqualTo(15_750);
        assertThat(snapshot.playable()).isTrue();
        assertThat(snapshot.ended()).isTrue();
    }

    @Test
    void ignoresPlaylistUntilReferencedFilesAreComplete() throws Exception {
        Files.writeString(tempDir.resolve("index.m3u8"), """
                #EXTM3U
                #EXT-X-MAP:URI="init.mp4"
                #EXTINF:2.0,
                segment-000000.m4s
                """);

        assertThat(new HlsPlaylistReader().inspect(tempDir, 0, 0, "/hls/0/index.m3u8"))
                .isEmpty();
    }
}
