package com.cyberheist.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence and lifecycle rules for refresh tokens: issue, rotate, revoke.
 *
 * <p>Rotation is enforced on every refresh, so a stolen token is usable at most
 * once and its reuse is detectable.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    private final RefreshTokenRepository repository;
    private final RefreshTokenGenerator generator;
    private final Clock clock;

    @Autowired
    public RefreshTokenService(RefreshTokenRepository repository, RefreshTokenGenerator generator) {
        this(repository, generator, Clock.systemUTC());
    }

    RefreshTokenService(RefreshTokenRepository repository, RefreshTokenGenerator generator, Clock clock) {
        this.repository = repository;
        this.generator = generator;
        this.clock = clock;
    }

    /**
     * The raw token paired with its stored record.
     *
     * <p>The raw value only exists in this object long enough to be returned
     * once to the client; it is never persisted.
     */
    public record IssuedToken(String rawToken, RefreshToken entity) {
    }

    /**
     * Looks up a stored token by the raw value the client presented.
     *
     * <p>Hashing happens here so the hashing rule stays in one place.
     */
    public Optional<RefreshToken> findByHashed(String rawToken) {
        return findByRawToken(rawToken);
    }

    /**

    /**
     * Issues a token and returns both halves of it.
     *
     * <p>The raw value is only ever available here, at the moment of creation.
     */
    @Transactional
    public IssuedToken issueWithRawValue(UUID userId) {
        RefreshTokenGenerator.GeneratedRefreshToken generated = generator.generate();
        Instant now = clock.instant();

        RefreshToken entity = repository.save(new RefreshToken(
                UUID.randomUUID(),
                userId,
                generated.hash(),
                now.plusSeconds(generator.expiresInSeconds()),
                now
        ));
        return new IssuedToken(generated.rawToken(), entity);
    }

    /**
     * Revokes a stored token.
     *
     * <p>Idempotent: revoking an unknown or already-revoked token is a no-op,
     * so logging out twice is not an error.
     *
     * @return {@code true} when a live token was actually revoked
     */
    @Transactional
    public boolean revokeByRawToken(String rawToken) {
        return findByRawToken(rawToken)
                .filter(token -> !token.isRevoked())
                .map(token -> {
                    token.revoke();
                    repository.save(token);
                    return true;
                })
                .orElse(false);
    }

    /**
     * Revokes every live token belonging to a user.
     *
     * <p>Used when an account is disabled, so outstanding sessions cannot
     * continue after the fact.
     */
    @Transactional
    public long revokeAllForUser(UUID userId) {
        List<RefreshToken> active = repository.findByUserIdAndRevokedFalse(userId);
        active.forEach(RefreshToken::revoke);
        repository.saveAll(active);
        return active.size();
    }

    private Optional<RefreshToken> findByRawToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        return repository.findByTokenHash(generator.hash(rawToken));
    }
}