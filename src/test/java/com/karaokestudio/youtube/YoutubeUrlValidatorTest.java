package com.karaokestudio.youtube;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class YoutubeUrlValidatorTest {

    private final YoutubeUrlValidator validator = new YoutubeUrlValidator(2_048);

    @Test
    void acceptsWatchShortsAndShortUrls() {
        assertThat(validator.validate("https://www.youtube.com/watch?v=abcdefghijk").videoId())
                .isEqualTo("abcdefghijk");
        assertThat(validator.validate("https://youtu.be/abcdefghijk?t=30").videoId())
                .isEqualTo("abcdefghijk");
        assertThat(validator.validate("https://m.youtube.com/shorts/abcdefghijk").videoId())
                .isEqualTo("abcdefghijk");
    }

    @Test
    void rejectsInvalidProtocolHostAndVideoId() {
        assertInvalid("http://www.youtube.com/watch?v=abcdefghijk");
        assertInvalid("https://www.youtube.com.evil.example/watch?v=abcdefghijk");
        assertInvalid("https://www.youtube.com/watch");
        assertInvalid("https://youtu.be/too-short");
        assertInvalid("https://www.youtube.com/watch?v=abcdefghijk&foo=%ZZ");
    }

    @Test
    void rejectsCredentialsNonDefaultPortAndOversizedUrl() {
        assertInvalid("https://user:pass@www.youtube.com/watch?v=abcdefghijk");
        assertInvalid("https://www.youtube.com:8443/watch?v=abcdefghijk");
        assertThatThrownBy(() -> validator.validate("https://www.youtube.com/watch?v=" + "a".repeat(2_049)))
                .isInstanceOf(YoutubeUrlException.class)
                .hasMessageContaining("longa");
    }

    private void assertInvalid(String url) {
        assertThatThrownBy(() -> validator.validate(url))
                .isInstanceOf(YoutubeUrlException.class);
    }
}
