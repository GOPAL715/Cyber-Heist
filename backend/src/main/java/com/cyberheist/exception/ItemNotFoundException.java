package com.cyberheist.exception;

import org.springframework.http.HttpStatus;

public class ItemNotFoundException extends ApiException {
    public ItemNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message);
    }
    public ItemNotFoundException() {
        this("Item not found");
    }
}
