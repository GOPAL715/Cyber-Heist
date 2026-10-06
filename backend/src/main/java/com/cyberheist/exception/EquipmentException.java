package com.cyberheist.exception;

import org.springframework.http.HttpStatus;

public class EquipmentException extends ApiException {
    public EquipmentException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
    public static EquipmentException notOwned() {
        return new EquipmentException("Item is not in your inventory");
    }
}
