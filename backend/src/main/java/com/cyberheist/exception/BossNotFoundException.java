package com.cyberheist.exception;

import org.springframework.http.HttpStatus;

/** The requested boss does not exist, or is not currently available. */
public class BossNotFoundException extends ApiException {

    public BossNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message);
    }

    public BossNotFoundException() {
        this("Boss not found");
    }
}