package com.cyberheist.exception;

import org.springframework.http.HttpStatus;

/** The requested skill does not exist, or is not currently available. */
public class SkillNotFoundException extends ApiException {

    public SkillNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message);
    }

    public SkillNotFoundException() {
        this("Skill not found");
    }
}