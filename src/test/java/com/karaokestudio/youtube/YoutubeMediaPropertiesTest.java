package com.karaokestudio.youtube;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class YoutubeMediaPropertiesTest {

    @Test
    void exposesTheOperationalDefaults() {
        var properties = new YoutubeMediaProperties();

        assertThat(properties.getFfmpegPath()).isEqualTo("ffmpeg");
        assertThat(properties.getMaxHeight()).isEqualTo(360);
        assertThat(properties.getMaxDuration()).hasSeconds(2 * 60 * 60);
        assertThat(properties.getMaxInputSize().toBytes()).isEqualTo(2L * 1024 * 1024 * 1024);
        assertThat(properties.getPreparationTimeout()).hasMinutes(15);
        assertThat(properties.getReadyTtl()).hasHours(2);
        assertThat(properties.getFailedTtl()).hasMinutes(10);
        assertThat(properties.getCacheSize().toBytes()).isEqualTo(5L * 1024 * 1024 * 1024);
        assertThat(properties.getActiveWorkers()).isEqualTo(2);
        assertThat(properties.getMaxQueue()).isEqualTo(8);
    }

    @Test
    void rejectsInvalidLimits() {
        var properties = new YoutubeMediaProperties();
        properties.setActiveWorkers(0);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limites");
    }
}
