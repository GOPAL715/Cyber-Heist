package com.cyberheist.exception;

import org.springframework.http.HttpStatus;

/**
 * A mission referenced a puzzle type that has no provider registered.
 *
 * <p>Always a server configuration fault rather than a player mistake, so it
 * reports as 500: the player cannot fix it and should not be told to try again.
 */
public class PuzzleProviderUnavailableException extends ApiException {

    public PuzzleProviderUnavailableException(String message) {
        super(HttpStatus.INTERNAL_SERVER_ERROR, message);
    }
}