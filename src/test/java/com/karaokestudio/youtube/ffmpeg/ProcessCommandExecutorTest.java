package com.karaokestudio.youtube.ffmpeg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

class ProcessCommandExecutorTest {

    @Test
    void executesArgumentListWithoutShell() throws Exception {
        var javaExecutable = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();

        var result = new ProcessCommandExecutor().execute(List.of(javaExecutable, "-version"), Duration.ofSeconds(5));

        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).contains("version");
    }

    @Test
    void reportsMissingExecutable() {
        assertThatThrownBy(() -> new ProcessCommandExecutor().execute(
                List.of("definitely-not-an-executable-karaoke-studio"), Duration.ofSeconds(1)))
                .isInstanceOf(FfmpegExecutionException.class)
                .hasMessageContaining("Não foi possível iniciar o processo");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
