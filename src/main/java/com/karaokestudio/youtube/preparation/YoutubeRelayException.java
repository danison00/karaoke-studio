package com.karaokestudio.youtube.preparation;

public class YoutubeRelayException extends RuntimeException {

    public YoutubeRelayException(String message) {
        super(message);
    }

    public YoutubeRelayException(String message, Throwable cause) {
        super(message, cause);
    }
}
