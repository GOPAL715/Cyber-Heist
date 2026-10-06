package com.cyberheist.exception;

import org.springframework.http.HttpStatus;

public class ItemUnavailableException extends ApiException {
    public ItemUnavailableException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
