package com.cyberheist.exception;

import org.springframework.http.HttpStatus;

/**
 * A completion was attempted without a solved puzzle.
 *
 * <p>This is what closes the Phase 2 shortcut where {@code POST /complete}
 * paid out on its own. The message is written for a player: it says what to do
 * next rather than what went wrong internally.
 */
public class PuzzleRequiredException extends ApiException {

    public PuzzleRequiredException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}