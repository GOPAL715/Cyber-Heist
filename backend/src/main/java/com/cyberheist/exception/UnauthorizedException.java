package com.cyberheist.exception;

import org.springframework.http.HttpStatus;

/**
 * 401 - credentials are missing, wrong, or the token is invalid/expired/revoked.
 *
 * <p>Messages are deliberately vague ("Invalid credentials") so the API cannot
 * be used to enumerate which accounts exist.
 */
public class UnauthorizedException extends ApiException {

    public UnauthorizedException(String message) {
        super(HttpStatus.UNAUTHORIZED, message);
    }

    public UnauthorizedException(String message, Throwable cause) {
        super(HttpStatus.UNAUTHORIZED, message, cause);
    }
}