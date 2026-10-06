package com.cyberheist.exception;

import org.springframework.http.HttpStatus;

/**
 * The player cannot start or continue this boss right now.
 *
 * <p>One type covers every reason - below the required level, on cooldown,
 * already fighting something, or out of energy - because each carries its own
 * player-safe explanation in the message.
 */
public class BossUnavailableException extends ApiException {

    public BossUnavailableException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }

    public BossUnavailableException(String message, HttpStatus status) {
        super(status, message);
    }
}