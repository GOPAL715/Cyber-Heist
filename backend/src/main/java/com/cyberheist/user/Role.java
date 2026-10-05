package com.cyberheist.user;

/**
 * Roles understood by the application.
 *
 * <p>Registration always assigns {@link #PLAYER}; {@link #ADMIN} can never be
 * requested by a client.
 */
public enum Role {
    PLAYER,
    ADMIN
}