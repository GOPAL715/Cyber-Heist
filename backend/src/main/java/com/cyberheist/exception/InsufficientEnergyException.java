package com.cyberheist.exception;

import org.springframework.http.HttpStatus;

/** Not enough energy to start this mission. */
public class InsufficientEnergyException extends ApiException {

    public InsufficientEnergyException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }

}
