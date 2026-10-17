package com.karaokestudio.youtube.ffmpeg;

import java.util.concurrent.CompletionStage;

public interface FfmpegProcess extends AutoCloseable {

    boolean isAlive();

    void cancel();

    CompletionStage<CommandResult> completion();

    @Override
    default void close() {
        cancel();
    }
}
