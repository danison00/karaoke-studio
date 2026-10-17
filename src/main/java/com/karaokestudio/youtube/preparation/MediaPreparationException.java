package com.karaokestudio.youtube.preparation;

public class MediaPreparationException extends RuntimeException {

    public MediaPreparationException(String message) {
        super(message);
    }

    public MediaPreparationException(String message, Throwable cause) {
        super(message, cause);
    }
}
