package com.karaokestudio.youtube;

public class UpstreamMediaException extends RuntimeException {

    public UpstreamMediaException(String message) {
        super(message);
    }

    public UpstreamMediaException(String message, Throwable cause) {
        super(message, cause);
    }
}
