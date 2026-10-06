package com.cyberheist.exception;

import org.springframework.http.HttpStatus;

/**
 * The submitted puzzle id does not exist, or does not belong to this player or
 * this mission.
 *
 * <p>One message covers all three cases on purpose. Distinguishing "no such
 * puzzle" from "that puzzle belongs to someone else" would confirm to a prober
 * that an id they guessed is real. From the player's point of view both simply
 * mean "not a puzzle of yours".
 */
public class PuzzleNotFoundException extends ApiException {

    public PuzzleNotFoundException() {
        super(HttpStatus.NOT_FOUND, "Puzzle not found for this mission");
    }
}