package com.cyberheist.exception;

import org.springframework.http.HttpStatus;

public class InsufficientCoinsException extends ApiException {
    public InsufficientCoinsException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
