package com.karaokestudio.youtube;

public class YoutubeUrlException extends RuntimeException {

    public YoutubeUrlException(String message) {
        super(message);
    }

    public YoutubeUrlException(String message, Throwable cause) {
        super(message, cause);
    }
}
