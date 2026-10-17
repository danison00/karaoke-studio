package com.karaokestudio.youtube.ffmpeg;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

class ProcessCommandExecutorCancellationTest {

    @Test
    void processHandleCanBeCancelledMoreThanOnce() {
        var process = new FakeProcess();

        process.cancel();
        process.cancel();

        assertThat(process.cancelCalls).isEqualTo(2);
    }

    private static final class FakeProcess implements FfmpegProcess {
        private int cancelCalls;

        @Override public boolean isAlive() { return false; }
        @Override public void cancel() { cancelCalls++; }
        @Override public java.util.concurrent.CompletionStage<CommandResult> completion() {
            return java.util.concurrent.CompletableFuture.completedFuture(new CommandResult(0, "", ""));
        }
    }
}
