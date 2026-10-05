package com.cyberheist.exception;

import org.springframework.http.HttpStatus;

/** Player level is too low for this mission. */
public class MissionLockedException extends ApiException {

    public MissionLockedException(String message) {
        super(HttpStatus.FORBIDDEN, message);
    }

}
