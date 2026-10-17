package com.karaokestudio.web;

public class RequestLimitException extends RuntimeException {

    public RequestLimitException(String message) {
        super(message);
    }
}
