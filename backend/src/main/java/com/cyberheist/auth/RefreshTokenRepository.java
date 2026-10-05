package com.cyberheist.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Every live token for an account, used when disabling a user. */
    java.util.List<RefreshToken> findByUserIdAndRevokedFalse(UUID userId);

    /** Housekeeping hook for a future scheduled cleanup job. */
    long deleteByExpiresAtBefore(Instant cutoff);
}