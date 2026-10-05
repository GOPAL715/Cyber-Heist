package com.cyberheist.auth;

import com.cyberheist.auth.dto.UserResponse;
import com.cyberheist.user.User;

/**
 * Converts entities to the DTOs that are safe to expose over the API.
 *
 * <p>A single place that decides which fields leave the backend, so a new
 * sensitive column cannot be leaked by accident.
 */
public final class UserMapper {

    private UserMapper() {
    }

    public static UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getEmail(), user.getRole());
    }
}