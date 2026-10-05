package com.cyberheist.common;

import java.time.Instant;
import java.util.Map;

/**
 * Consistent error envelope for every non-2xx API response.
 *
 * <p>Never carries stack traces, exception class names or internal details -
 * those go to the server log only.
 *
 * @param success   always {@code false}
 * @param message   safe, user facing summary
 * @param errors    optional field level validation messages, keyed by field name
 * @param path      request path that failed, useful for clients and tracing
 * @param timestamp server time the error was produced
 */
public record ApiErrorResponse(
        boolean success,
        String message,
        Map<String, String> errors,
        String path,
        Instant timestamp
) {
    public static ApiErrorResponse of(String message, String path) {
        return new ApiErrorResponse(false, message, null, path, Instant.now());
    }

    public static ApiErrorResponse of(String message, Map<String, String> errors, String path) {
        return new ApiErrorResponse(false, message, errors, path, Instant.now());
    }
}