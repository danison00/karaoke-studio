package com.karaokestudio.youtube;

public class RangeNotSatisfiableException extends RuntimeException {

    private final Long totalLength;

    public RangeNotSatisfiableException(String message) {
        this(message, null);
    }

    public RangeNotSatisfiableException(String message, Long totalLength) {
        super(message);
        this.totalLength = totalLength;
    }

    public Long totalLength() { return totalLength; }
}
