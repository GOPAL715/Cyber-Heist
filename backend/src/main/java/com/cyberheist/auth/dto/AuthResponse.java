package com.cyberheist.auth.dto;

import java.util.UUID;

/**
 * Tokens plus the authenticated account, returned by login and refresh.
 *
 * @param accessToken       short lived JWT, sent as {@code Authorization: Bearer ...}
 * @param refreshToken      opaque single-use token, stored hashed server side
 * @param tokenType         always {@code Bearer}
 * @param expiresIn         access token lifetime in seconds
 * @param refreshExpiresIn  refresh token lifetime in seconds
 * @param user              safe account information
 */
public record AuthResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        long refreshExpiresIn,
        UserResponse user
) {
    public static final String BEARER = "Bearer";

    public static AuthResponse of(String accessToken,
                                  String refreshToken,
                                  long expiresIn,
                                  long refreshExpiresIn,
                                  UserResponse user) {
        return new AuthResponse(accessToken, refreshToken, BEARER, expiresIn, refreshExpiresIn, user);
    }
}