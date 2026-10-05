package com.cyberheist.exception;

import org.springframework.http.HttpStatus;

/** 400 - the request is syntactically valid but semantically rejected. */
public class BadRequestException extends ApiException {

    public BadRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}