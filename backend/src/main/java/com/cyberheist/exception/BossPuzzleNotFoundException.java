package com.cyberheist.exception;

import org.springframework.http.HttpStatus;

/**
 * The submitted puzzle is not the one the player is currently facing.
 *
 * <p>Covers "not this player's puzzle", "belongs to another encounter",
 * "belongs to a mission rather than a boss" and "no such puzzle" identically, so
 * a prober cannot use the response to learn that an id they guessed is real.
 */
public class BossPuzzleNotFoundException extends ApiException {

    public BossPuzzleNotFoundException() {
        super(HttpStatus.NOT_FOUND, "Puzzle not found for this encounter");
    }
}