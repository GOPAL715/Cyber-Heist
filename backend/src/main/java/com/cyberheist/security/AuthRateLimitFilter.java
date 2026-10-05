package com.cyberheist.security;

import com.cyberheist.common.ApiErrorResponse;
import com.cyberheist.config.RateLimitProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixed-window rate limiter for the authentication endpoints.
 *
 * <p>Guards credential stuffing and username enumeration. Keyed by client IP,
 * tracked in memory: adequate for a single Phase 1 instance. Horizontal
 * scaling would move this state to a shared store.
 *
 * <p>Only failed attempts are counted, so a legitimate user who signs in
 * correctly is never locked out by their own history.
 */
@Component
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthRateLimitFilter.class);

    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public AuthRateLimitFilter(RateLimitProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, Clock.systemUTC());
    }

    AuthRateLimitFilter(RateLimitProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        if (!properties.enabled()) {
            return true;
        }
        String path = request.getRequestURI();
        return !("/api/v1/auth/login".equals(path) || "/api/v1/auth/register".equals(path));
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        if (isBlocked(clientKey(request))) {
            log.warn("Rate limit exceeded for {} on {}", clientKey(request), request.getRequestURI());
            writeTooManyRequests(response, request);
            return;
        }
        filterChain.doFilter(request, response);
    }

    /** Records a failed attempt for this client. */
    public void recordFailure(String clientKey) {
        if (!properties.enabled()) {
            return;
        }
        windows.compute(clientKey, (key, existing) -> {
            Instant now = clock.instant();
            if (existing == null || existing.isExpired(now, properties.window())) {
                return new Window(now, new AtomicInteger(1));
            }
            existing.attempts().incrementAndGet();
            return existing;
        });
    }

    /** Clears the failure history, called after a successful login. */
    public void recordSuccess(String clientKey) {
        windows.remove(clientKey);
    }

    private boolean isBlocked(String clientKey) {
        Window window = windows.get(clientKey);
        if (window == null) {
            return false;
        }
        if (window.isExpired(clock.instant(), properties.window())) {
            windows.remove(clientKey, window);
            return false;
        }
        return window.attempts().get() >= properties.maxAttempts();
    }

    private void writeTooManyRequests(HttpServletResponse response, HttpServletRequest request) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiErrorResponse.of(
                "Too many attempts. Please try again later.",
                request.getRequestURI()));
    }

    private String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }

    /** Small mutable counter plus the instant the current window opened. */
    private record Window(Instant startedAt, AtomicInteger attempts) {
        boolean isExpired(Instant now, Duration window) {
            return Duration.between(startedAt, now).compareTo(window) >= 0;
        }
    }
}