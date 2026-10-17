package com.karaokestudio.youtube.preparation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class HlsPreparationSnapshotTest {

    @Test
    void snapshotCopiesAvailableRangesAndKeepsProducerSeparateFromPlayback() {
        var ranges = new ArrayList<>(List.of(
                new HlsRange(0, 0, 20_000, "/hls/0/index.m3u8")));
        var snapshot = new HlsPreparationSnapshot(
                UUID.randomUUID(), "abcdefghijk", "Título", PlaybackStatus.STREAMING,
                PreparationMode.HLS, 120_000, 0, 1L, 4, 30_000, 0,
                "/hls/0/index.m3u8", ranges, null, null, null);

        ranges.clear();

        assertThat(snapshot.playbackGeneration()).isZero();
        assertThat(snapshot.producerGeneration()).isEqualTo(1L);
        assertThat(snapshot.availableRanges()).hasSize(1);
        assertThat(snapshot.status()).isEqualTo(PlaybackStatus.STREAMING);
    }
}
