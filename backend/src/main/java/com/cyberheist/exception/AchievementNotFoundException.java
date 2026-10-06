package com.cyberheist.exception;

import org.springframework.http.HttpStatus;

/**
 * An achievement code that does not exist, or is not active.
 *
 * <p>There is deliberately no separate "that is not your achievement" error. The only
 * state routes take an achievement code and always answer about the caller, so the
 * question of reaching another player's achievement state cannot be asked at all -
 * there is no parameter to put their id in.
 */
public class AchievementNotFoundException extends ApiException {

    public AchievementNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message);
    }

    public AchievementNotFoundException() {
        this("Achievement not found");
    }
}
