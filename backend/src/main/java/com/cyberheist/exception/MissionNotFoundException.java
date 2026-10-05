package com.cyberheist.exception;

import org.springframework.http.HttpStatus;

/** Mission no longer exists. */
public class MissionNotFoundException extends ApiException {

    public MissionNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message);
    }

}
