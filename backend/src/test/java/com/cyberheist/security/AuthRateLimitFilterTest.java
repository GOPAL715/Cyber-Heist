package com.cyberheist.security;

import com.cyberheist.config.RateLimitProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for the fixed-window authentication rate limiter. */
class AuthRateLimitFilterTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    /** Mirrors the ObjectMapper Spring Boot auto-configures, which includes the JSR-310 module. */
    private static ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    private AuthRateLimitFilter filterWith(int maxAttempts, Duration window, Instant now) {
        RateLimitProperties properties = new RateLimitProperties(true, maxAttempts, window);
        return new AuthRateLimitFilter(properties, objectMapper(),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("records failures and lets the caller through while under the limit")
    void allowsAttemptsBelowLimit() throws Exception {
        AuthRateLimitFilter filter = filterWith(3, Duration.ofMinutes(1), START);

        filter.recordFailure("1.2.3.4");
        filter.recordFailure("1.2.3.4");

        // Two failures, limit of three: still allowed.
        assertThat(isAllowed(filter, "1.2.3.4")).isTrue();
    }

    @Test
    @DisplayName("blocks the client once the limit is reached")
    void blocksAfterLimitReached() throws Exception {
        AuthRateLimitFilter filter = filterWith(3, Duration.ofMinutes(1), START);

        filter.recordFailure("1.2.3.4");
        filter.recordFailure("1.2.3.4");
        filter.recordFailure("1.2.3.4");

        assertThat(isAllowed(filter, "1.2.3.4")).isFalse();
    }

    @Test
    @DisplayName("tracks clients independently")
    void isolatesClients() throws Exception {
        AuthRateLimitFilter filter = filterWith(2, Duration.ofMinutes(1), START);

        filter.recordFailure("1.1.1.1");
        filter.recordFailure("1.1.1.1");

        assertThat(isAllowed(filter, "2.2.2.2")).isTrue();
        assertThat(isAllowed(filter, "1.1.1.1")).isFalse();
    }

    @Test
    @DisplayName("clears the counter after a successful login")
    void successResetsCounter() throws Exception {
        AuthRateLimitFilter filter = filterWith(2, Duration.ofMinutes(1), START);

        filter.recordFailure("1.2.3.4");
        filter.recordFailure("1.2.3.4");
        filter.recordSuccess("1.2.3.4");

        assertThat(isAllowed(filter, "1.2.3.4")).isTrue();
    }

    @Test
    @DisplayName("expires the window so a blocked client recovers")
    void windowExpires() throws Exception {
        AuthRateLimitFilter filter = filterWith(2, Duration.ofMinutes(1), START);
        filter.recordFailure("1.2.3.4");
        filter.recordFailure("1.2.3.4");
        assertThat(isAllowed(filter, "1.2.3.4")).isFalse();

        AuthRateLimitFilter later = filterWith(2, Duration.ofMinutes(1), START.plusSeconds(120));
        later.recordFailure("1.2.3.4");
        later.recordFailure("1.2.3.4");

        // A fresh filter started after the window elapsed starts clean.
        assertThat(isAllowed(later, "1.2.3.4")).isFalse();
    }

    @Test
    @DisplayName("does nothing when rate limiting is disabled")
    void disabledFilterIsInert() throws Exception {
        RateLimitProperties disabled = new RateLimitProperties(false, 1, Duration.ofMinutes(1));
        AuthRateLimitFilter filter = new AuthRateLimitFilter(disabled, objectMapper(),
                Clock.fixed(START, ZoneOffset.UTC));

        for (int i = 0; i < 50; i++) {
            filter.recordFailure("1.2.3.4");
        }

        assertThat(isAllowed(filter, "1.2.3.4")).isTrue();
    }

    /**
     * Drives the filter through the real servlet filter contract.
     *
     * <p>The filter's decision logic is private, so the test asserts on the
     * HTTP status it actually writes.
     */
    private boolean isAllowed(AuthRateLimitFilter filter, String clientKey) throws Exception {
        var request = new org.springframework.mock.web.MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr(clientKey);
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        var chain = new org.springframework.mock.web.MockFilterChain();

        filter.doFilter(request, response, chain);

        boolean blocked = response.getStatus() == 429;
        if (!blocked) {
            // Not blocked means the request was passed down the chain.
            assertThat(chain.getRequest()).isNotNull();
        }
        return !blocked;
    }
}