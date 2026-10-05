package com.cyberheist.security;

import com.cyberheist.exception.UnauthorizedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Reads the authenticated identity from the security context.
 *
 * <p>This is the only way controllers obtain the current user id. Identity is
 * therefore never taken from a request parameter or request body, which is what
 * stops a client from asking for someone else's data.
 */
@Component
public class CurrentUser {

    /** The authenticated account id, if the request carried a valid access token. */
    public Optional<UUID> id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedPrincipal principal)) {
            return Optional.empty();
        }
        return Optional.of(principal.userId());
    }

    /** The authenticated account id, or 401 when the request is anonymous. */
    public UUID requireId() {
        return id().orElseThrow(() -> new UnauthorizedException("Authentication required"));
    }
}