package com.cyberheist.exception;

import org.springframework.http.HttpStatus;

/**
 * This puzzle has already been answered, so the one submission it allows is
 * spent.
 */
public class PuzzleAlreadySubmittedException extends ApiException {

    public PuzzleAlreadySubmittedException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}