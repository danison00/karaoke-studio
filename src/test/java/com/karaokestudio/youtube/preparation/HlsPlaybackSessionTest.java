package com.karaokestudio.youtube.preparation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class HlsPlaybackSessionTest {

    @Test
    void newerSeekSequenceWinsWhenRequestsArriveOutOfOrder() {
        var session = new HlsPlaybackSession(UUID.randomUUID(), "video", "Título", 300_000);
        session.publish(new HlsGenerationSnapshot(0, 0, 20_000, "/api/youtube/preparations/x/hls/0/index.m3u8", true, false));

        assertThat(session.acceptSeek(2, 120_000).startGeneration()).isEqualTo(1L);
        assertThat(session.acceptSeek(1, 5_000).changed()).isFalse();
        assertThat(session.snapshot().acceptedSeekSequence()).isEqualTo(2L);
        assertThat(session.snapshot().requestedPositionMs()).isEqualTo(120_000L);
    }

    @Test
    void seekInsideCachedRangeChangesPlaybackWithoutReplacingProducer() {
        var session = new HlsPlaybackSession(UUID.randomUUID(), "video", "Título", 300_000);
        session.publish(new HlsGenerationSnapshot(0, 0, 40_000, "/api/youtube/preparations/x/hls/0/index.m3u8", true, false));
        session.beginProducer(0, null, null);

        var decision = session.acceptSeek(1, 12_000);

        assertThat(decision.changed()).isTrue();
        assertThat(decision.startGeneration()).isNull();
        assertThat(decision.cancelGeneration()).isNull();
        assertThat(session.snapshot().playbackGeneration()).isEqualTo(0L);
        assertThat(session.snapshot().producerGeneration()).isEqualTo(0L);
        assertThat(session.snapshot().timelineOffsetMs()).isEqualTo(0L);
    }

    @Test
    void failedNewProducerLeavesOldRangeRecoverable() {
        var session = new HlsPlaybackSession(UUID.randomUUID(), "video", "Título", 300_000);
        session.publish(new HlsGenerationSnapshot(0, 0, 40_000, "/api/youtube/preparations/x/hls/0/index.m3u8", true, false));
        session.acceptSeek(1, 120_000);
        session.beginProducer(1, null, "/api/youtube/preparations/x/hls/1/index.m3u8");
        session.producerFailed(1, "falha nova");

        assertThat(session.snapshot().status()).isEqualTo(PlaybackStatus.DEGRADED);
        assertThat(session.snapshot().availableRanges()).hasSize(1);
        assertThat(session.snapshot().recoverableError()).isEqualTo("falha nova");
    }

    @Test
    void includesSessionIdWhenProducerStopsWithoutAReason() {
        var id = UUID.randomUUID();
        var session = new HlsPlaybackSession(id, "video", "Título", 300_000);

        session.beginProducer(0, null, "/api/youtube/preparations/x/hls/0/index.m3u8");
        session.producerFinished(0, false, null);

        assertThat(session.snapshot().error()).contains(id.toString());
    }
}
