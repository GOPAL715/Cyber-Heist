package com.cyberheist.auth;

import com.cyberheist.auth.dto.AuthResponse;
import com.cyberheist.auth.dto.LoginRequest;
import com.cyberheist.auth.dto.LogoutRequest;
import com.cyberheist.auth.dto.RefreshTokenRequest;
import com.cyberheist.auth.dto.RegisterRequest;
import com.cyberheist.auth.dto.UserResponse;
import com.cyberheist.common.ApiResponse;
import com.cyberheist.security.AuthRateLimitFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Authentication endpoints.
 *
 * <p>Controllers stay thin: validation happens through the DTO constraints and
 * all logic lives in the services.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final RegistrationService registrationService;
    private final AuthService authService;
    private final AuthRateLimitFilter rateLimitFilter;

    public AuthController(RegistrationService registrationService,
                          AuthService authService,
                          AuthRateLimitFilter rateLimitFilter) {
        this.registrationService = registrationService;
        this.authService = authService;
        this.rateLimitFilter = rateLimitFilter;
    }

    /** Registers a new player and returns the created account. */
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<UserResponse>> register(@Valid @RequestBody RegisterRequest request) {
        UserResponse created = registrationService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(created, "Account created successfully"));
    }

    /** Exchanges credentials for an access/refresh token pair. */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request,
                                                          HttpServletRequest httpRequest) {
        AuthResponse response;
        try {
            response = authService.login(request);
        } catch (RuntimeException ex) {
            // Only failed attempts count towards the rate limit.
            rateLimitFilter.recordFailure(clientKey(httpRequest));
            throw ex;
        }
        rateLimitFilter.recordSuccess(clientKey(httpRequest));
        return ResponseEntity.ok(ApiResponse.of(response, "Login successful"));
    }

    /** Rotates the refresh token and issues a new access token. */
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ResponseEntity.ok(ApiResponse.of(authService.refresh(request.refreshToken()),
                "Token refreshed"));
    }

    /**
     * Revokes the supplied refresh token.
     *
     * <p>Public by design: a client whose access token has already expired must
     * still be able to end its session. The refresh token itself is the
     * credential being presented, so it is always verified.
     */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.ok(ApiResponse.of(Map.of("revoked", true), "Logged out successfully"));
    }

    private String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }
}