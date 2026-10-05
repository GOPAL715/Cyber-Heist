package com.cyberheist.exception;

import org.springframework.http.HttpStatus;

/** 409 - the request conflicts with existing state (duplicate username or email). */
public class ConflictException extends ApiException {

    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}