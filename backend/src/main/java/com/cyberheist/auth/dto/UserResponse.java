package com.cyberheist.auth.dto;

import com.cyberheist.user.Role;

import java.util.UUID;

/**
 * Safe representation of an account.
 *
 * <p>Deliberately excludes {@code passwordHash} - this is the only shape of a
 * user that ever leaves the backend.
 */
public record UserResponse(
        UUID id,
        String username,
        String email,
        Role role
) {
}