package com.cyberheist.exception;

import org.springframework.http.HttpStatus;

/**
 * Base type for expected, safely reportable failures.
 *
 * <p>The {@link #getMessage()} of any subclass is written straight into the API
 * response, so implementations must never embed stack traces, SQL or secrets.
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;

    protected ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    protected ApiException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}