package com.karaokestudio.youtube.ffmpeg;

public class FfmpegUnavailableException extends FfmpegExecutionException {

    public FfmpegUnavailableException(String message) {
        super(message);
    }

    public FfmpegUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
