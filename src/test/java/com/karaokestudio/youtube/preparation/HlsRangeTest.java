package com.karaokestudio.youtube.preparation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class HlsRangeTest {

    @Test
    void acceptsLogicalRangeAndExposesPlaylistUrl() {
        var range = new HlsRange(3, 12_000, 18_000, "/api/youtube/preparations/id/hls/3/index.m3u8");

        assertThat(range.generation()).isEqualTo(3);
        assertThat(range.fromMs()).isEqualTo(12_000);
        assertThat(range.toMs()).isEqualTo(18_000);
        assertThat(range.playlistUrl()).contains("/hls/3/");
    }

    @Test
    void rejectsInvalidRange() {
        assertThatThrownBy(() -> new HlsRange(0, 10_000, 9_999, "/playlist.m3u8"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HlsRange(-1, 0, 1, "/playlist.m3u8"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
