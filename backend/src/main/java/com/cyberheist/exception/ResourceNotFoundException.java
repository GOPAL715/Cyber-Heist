package com.cyberheist.exception;

import org.springframework.http.HttpStatus;

/** 404 - the requested resource does not exist. */
public class ResourceNotFoundException extends ApiException {

    public ResourceNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message);
    }

    public static ResourceNotFoundException of(String resource, Object identifier) {
        return new ResourceNotFoundException(resource + " not found: " + identifier);
    }
}