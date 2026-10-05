package com.cyberheist.exception;

import org.springframework.http.HttpStatus;

/** This mission has not been started yet. */
public class MissionNotStartedException extends ApiException {

    public MissionNotStartedException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }

}
