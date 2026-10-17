package com.karaokestudio.youtube.ffmpeg;

public class FfmpegExecutionException extends RuntimeException {

    public FfmpegExecutionException(String message) {
        super(message);
    }

    public FfmpegExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
