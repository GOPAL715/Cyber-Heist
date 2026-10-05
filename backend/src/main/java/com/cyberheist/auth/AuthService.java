package com.cyberheist.auth;

import com.cyberheist.auth.dto.AuthResponse;
import com.cyberheist.auth.dto.LoginRequest;
import com.cyberheist.config.JwtProperties;
import com.cyberheist.exception.UnauthorizedException;
import com.cyberheist.security.JwtTokenProvider;
import com.cyberheist.user.User;
import com.cyberheist.user.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;

/**
 * Login, token refresh and logout.
 *
 * <p>Credential failures are always reported as "Invalid email or password"
 * whether or not the account exists, which prevents user enumeration.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String GENERIC_FAILURE = "Invalid email or password";

    /**
     * A valid BCrypt hash of a value nobody can supply. Comparing against it
     * keeps the cost of rejecting an unknown account the same as rejecting a
     * wrong password, so timing does not disclose which emails are registered.
     */
    private static final String DUMMY_HASH = "$2a$12$C6UzMDM.H6dfI/f/IKcEe.7XyD4OcC1TgL0Z7XhZ0k5Q0Rr9X3mBJi";

    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;
    private final Clock clock;

    @Autowired
    public AuthService(UserRepository userRepository,
                       RefreshTokenService refreshTokenService,
                       PasswordEncoder passwordEncoder,
                       JwtTokenProvider jwtTokenProvider,
                       JwtProperties jwtProperties) {
        this(userRepository, refreshTokenService, passwordEncoder, jwtTokenProvider, jwtProperties,
                Clock.systemUTC());
    }

    AuthService(UserRepository userRepository,
                RefreshTokenService refreshTokenService,
                PasswordEncoder passwordEncoder,
                JwtTokenProvider jwtTokenProvider,
                JwtProperties jwtProperties,
                Clock clock) {
        this.userRepository = userRepository;
        this.refreshTokenService = refreshTokenService;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtProperties = jwtProperties;
        this.clock = clock;
    }
    /**
     * Verifies credentials and issues a fresh token pair.
     *
     * @throws UnauthorizedException if the credentials do not match, or the
     *                               account is disabled
     */
    @Transactional
    public AuthResponse login(LoginRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        User user = userRepository.findByEmailIgnoreCase(email).orElse(null);

        if (user == null) {
            // Still pay the hashing cost so timings match the "wrong password" path.
            passwordEncoder.matches(request.password(), DUMMY_HASH);
            throw new UnauthorizedException(GENERIC_FAILURE);
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new UnauthorizedException(GENERIC_FAILURE);
        }

        if (!user.isEnabled()) {
            log.info("Rejected login for disabled account {}", user.getId());
            throw new UnauthorizedException("This account has been disabled");
        }

        return issueTokens(user);
    }

    /**
     * Exchanges a refresh token for a new access token.
     *
     * <p>The presented token is revoked and replaced, so each refresh token is
     * single use and replaying one is detectable.
     *
     * @throws UnauthorizedException if the token is unknown, expired or revoked
     */
    @Transactional
    public AuthResponse refresh(String rawRefreshToken) {
        Instant now = clock.instant();

        RefreshToken stored = resolveUsableToken(rawRefreshToken, now);
        stored.revoke();

        User user = userRepository.findById(stored.getUserId())
                .orElseThrow(() -> new UnauthorizedException("Invalid refresh token"));

        if (!user.isEnabled()) {
            log.info("Rejected refresh for disabled account {}", user.getId());
            throw new UnauthorizedException("This account has been disabled");
        }

        return issueTokens(user);
    }

    /**
     * Revokes the supplied refresh token.
     *
     * <p>Idempotent and deliberately silent: the response never discloses
     * whether the token existed.
     */
    @Transactional
    public void logout(String rawRefreshToken) {
        boolean revoked = refreshTokenService.revokeByRawToken(rawRefreshToken);
        log.debug("Logout processed; token {}", revoked ? "revoked" : "already invalid");
    }

    /** Resolves a raw token to its record, rejecting anything unusable. */
    private RefreshToken resolveUsableToken(String rawRefreshToken, Instant now) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new UnauthorizedException("Invalid refresh token");
        }

        RefreshToken stored = refreshTokenService.findByHashed(rawRefreshToken)
                .orElseThrow(() -> new UnauthorizedException("Invalid refresh token"));

        if (stored.isRevoked()) {
            // Reuse of a rotated token suggests it leaked, so drop every session.
            log.warn("Revoked refresh token reused for user {}; revoking all sessions", stored.getUserId());
            refreshTokenService.revokeAllForUser(stored.getUserId());
            throw new UnauthorizedException("Invalid refresh token");
        }

        if (stored.isExpired(now)) {
            throw new UnauthorizedException("Refresh token has expired");
        }

        return stored;
    }

    private AuthResponse issueTokens(User user) {
        String accessToken = jwtTokenProvider.createAccessToken(user);
        RefreshTokenService.IssuedToken refreshToken = refreshTokenService.issueWithRawValue(user.getId());

        return AuthResponse.of(
                accessToken,
                refreshToken.rawToken(),
                jwtProperties.accessTokenExpiresInSeconds(),
                jwtProperties.refreshTokenExpiresInSeconds(),
                UserMapper.toResponse(user)
        );
    }
}