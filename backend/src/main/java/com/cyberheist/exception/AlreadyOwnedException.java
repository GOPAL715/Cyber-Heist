package com.cyberheist.exception;

import org.springframework.http.HttpStatus;

public class AlreadyOwnedException extends ApiException {
    public AlreadyOwnedException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
