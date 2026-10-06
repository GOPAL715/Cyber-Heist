package com.cyberheist.exception;

import org.springframework.http.HttpStatus;

/** No live boss encounter for this player. */
public class NoActiveEncounterException extends ApiException {

    public NoActiveEncounterException() {
        super(HttpStatus.NOT_FOUND, "No active boss encounter");
    }
}