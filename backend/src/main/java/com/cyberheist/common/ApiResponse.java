package com.cyberheist.common;

import java.time.Instant;

/**
 * Consistent success envelope for every {@code 2xx} API response.
 *
 * @param success   always {@code true}
 * @param data      the payload, may be {@code null} for empty responses
 * @param message   human readable, safe to show to end users
 * @param timestamp server time the response was produced
 */
public record ApiResponse<T>(boolean success, T data, String message, Instant timestamp) {

    public static <T> ApiResponse<T> of(T data) {
        return new ApiResponse<>(true, data, "OK", Instant.now());
    }

    public static <T> ApiResponse<T> of(T data, String message) {
        return new ApiResponse<>(true, data, message, Instant.now());
    }

    /** For responses that carry no payload, such as logout. */
    public static ApiResponse<Void> message(String message) {
        return new ApiResponse<>(true, null, message, Instant.now());
    }
}