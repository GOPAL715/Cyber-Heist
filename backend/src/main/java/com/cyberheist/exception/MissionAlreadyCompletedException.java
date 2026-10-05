package com.cyberheist.exception;

import org.springframework.http.HttpStatus;

/** This mission has already been completed. */
public class MissionAlreadyCompletedException extends ApiException {

    public MissionAlreadyCompletedException(String message) {
        super(HttpStatus.CONFLICT, message);
    }

}
