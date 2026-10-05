package com.cyberheist.exception;

import org.springframework.http.HttpStatus;

/** 429 - too many authentication attempts from this client. */
public class RateLimitExceededException extends ApiException {

    public RateLimitExceededException(String message) {
        super(HttpStatus.TOO_MANY_REQUESTS, message);
    }
}