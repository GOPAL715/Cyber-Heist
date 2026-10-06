package com.cyberheist.exception;

import org.springframework.http.HttpStatus;

/**
 * The player cannot take this skill level right now.
 *
 * <p>One type covers every reason a skill is unavailable - a prerequisite is
 * unmet, the balance is short, or the skill is already maxed - because from the
 * player's point of view they are the same event and each carries its own
 * player-safe explanation in the message.
 */
public class SkillUnavailableException extends ApiException {

    public SkillUnavailableException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}