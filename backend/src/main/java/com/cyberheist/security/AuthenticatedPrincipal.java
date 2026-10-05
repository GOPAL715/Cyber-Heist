package com.cyberheist.security;

import com.cyberheist.user.Role;

import java.util.UUID;

/**
 * The identity carried by a verified access token.
 *
 * @param userId the account id taken from the {@code sub} claim
 * @param role   the role taken from the {@code role} claim
 */
public record AuthenticatedPrincipal(UUID userId, Role role) {
}