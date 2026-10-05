package com.cyberheist.security;

import com.cyberheist.config.JwtProperties;
import com.cyberheist.user.Role;
import com.cyberheist.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Issues and verifies JWT access tokens.
 *
 * <p>Only the minimum useful claims are written: {@code sub}, {@code role},
 * {@code iat}, {@code exp} and {@code iss}. Email, username and any other
 * personal data are deliberately left out of the token so they cannot be read
 * from a token without decoding it, and so a token stays valid if a user
 * renames their account.
 */
@Component
public class JwtTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);

    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_TOKEN_TYPE = "typ";
    private static final String TOKEN_TYPE_ACCESS = "access";
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey signingKey;
    private final String issuer;
    private final JwtProperties properties;

    public JwtTokenProvider(JwtProperties properties) {
        this.properties = properties;
        this.issuer = properties.issuer();

        byte[] keyBytes = properties.secret() == null
                ? new byte[0]
                : properties.secret().getBytes(StandardCharsets.UTF_8);

        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "app.security.jwt.secret must be at least " + MIN_SECRET_BYTES
                            + " bytes for HS256. Set the JWT_SECRET environment variable.");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        log.info("JWT provider initialised (issuer='{}', accessTtl={}, refreshTtl={})",
                issuer, properties.accessTokenExpiration(), properties.refreshTokenExpiration());
    }

    /**
     * Creates a signed access token for the given user.
     *
     * @return the compact JWS string to hand to the client
     */
    public String createAccessToken(User user) {
        Instant now = Instant.now();
        Instant expiry = now.plus(properties.accessTokenExpiration());

        return Jwts.builder()
                .issuer(issuer)
                .subject(user.getId().toString())
                .claim(CLAIM_ROLE, user.getRole().name())
                .claim(CLAIM_TOKEN_TYPE, TOKEN_TYPE_ACCESS)
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Parses and verifies a token.
     *
     * @throws ExpiredJwtException when the token is well formed but past its expiry
     * @throws JwtException when the signature, issuer or structure is invalid
     */
    public Claims parseClaims(String token) throws JwtException {
        return Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Validates a token and maps it back to a principal.
     *
     * @return the authenticated principal, or {@code null} if the token is missing,
     *         malformed, expired, or not an access token
     */
    public AuthenticatedPrincipal resolvePrincipal(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            Claims claims = parseClaims(token);

            if (!TOKEN_TYPE_ACCESS.equals(claims.get(CLAIM_TOKEN_TYPE, String.class))) {
                log.debug("Rejected token that is not an access token");
                return null;
            }

            String subject = claims.getSubject();
            if (subject == null) {
                return null;
            }

            String role = claims.get(CLAIM_ROLE, String.class);
            Role resolvedRole = Role.valueOf(role);

            return new AuthenticatedPrincipal(UUID.fromString(subject), resolvedRole);
        } catch (ExpiredJwtException ex) {
            log.debug("Rejected expired JWT");
            return null;
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("Rejected invalid JWT: {}", ex.getMessage());
            return null;
        }
    }
}