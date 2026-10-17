package com.karaokestudio.youtube;

public class YoutubeResolutionException extends RuntimeException {

    public YoutubeResolutionException(String message) {
        super(message);
    }

    public YoutubeResolutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
