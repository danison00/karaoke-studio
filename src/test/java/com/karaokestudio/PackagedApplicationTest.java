package com.karaokestudio;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class PackagedApplicationTest {

    @Test
    void runtimeClasspathContainsAngularShellWorkletAndBackend() {
        assertThat(new ClassPathResource("static/index.html").exists()).isTrue();
        assertThat(new ClassPathResource("static/audio-worklets/soundtouch-processor.js").exists()).isTrue();
        assertThat(new ClassPathResource("com/karaokestudio/KaraokeStudioApplication.class").exists()).isTrue();
    }
}
