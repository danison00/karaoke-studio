package com.karaokestudio.youtube;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class UpstreamStreamClientTest {

    @Test
    void parsesSingleByteRange() {
        var range = HttpRange.parse("bytes=10-99");

        assertThat(range.start()).isEqualTo(10);
        assertThat(range.end()).isEqualTo(99);
        assertThat(range.headerValue()).isEqualTo("bytes=10-99");
    }

    @Test
    void parsesSuffixByteRange() {
        var range = HttpRange.parse("bytes=-500");

        assertThat(range.start()).isNull();
        assertThat(range.end()).isEqualTo(500);
    }

    @Test
    void rejectsMultipleMalformedAndNonByteRanges() {
        assertThatThrownBy(() -> HttpRange.parse("bytes=0-1,2-3"))
                .isInstanceOf(RangeNotSatisfiableException.class);
        assertThatThrownBy(() -> HttpRange.parse("items=0-1"))
                .isInstanceOf(RangeNotSatisfiableException.class);
        assertThatThrownBy(() -> HttpRange.parse("bytes=abc-1"))
                .isInstanceOf(RangeNotSatisfiableException.class);
    }
}
