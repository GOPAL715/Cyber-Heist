package com.cyberheist.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Registration request.
 *
 * <p>There is deliberately no {@code role} field: a client cannot ask to become
 * an administrator.
 */
public record RegisterRequest(

        @NotBlank(message = "username is required")
        @Size(min = 3, max = 32, message = "username must be between 3 and 32 characters")
        @Pattern(regexp = "^[a-zA-Z0-9_]+$",
                message = "username may only contain letters, numbers and underscores")
        String username,

        @NotBlank(message = "email is required")
        @Email(message = "email must be a valid email address")
        @Size(max = 255, message = "email must not exceed 255 characters")
        String email,

        @NotBlank(message = "password is required")
        @Size(min = 8, max = 72, message = "password must be between 8 and 72 characters")
        @Pattern(
                regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^a-zA-Z0-9]).+$",
                message = "password must contain at least one lowercase letter, one uppercase letter, one digit and one special character")
        String password
) {
}