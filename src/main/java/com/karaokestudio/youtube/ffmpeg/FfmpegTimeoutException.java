package com.karaokestudio.youtube.ffmpeg;

public class FfmpegTimeoutException extends FfmpegExecutionException {

    public FfmpegTimeoutException(String message) {
        super(message);
    }

    public FfmpegTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
