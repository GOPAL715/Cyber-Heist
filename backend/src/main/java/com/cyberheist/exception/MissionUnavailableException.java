package com.cyberheist.exception;

import org.springframework.http.HttpStatus;

/** This mission is not currently available. */
public class MissionUnavailableException extends ApiException {

    public MissionUnavailableException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }

}
