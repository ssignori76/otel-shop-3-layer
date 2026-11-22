package com.commerce.synthetic.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenProviderTest {

    private static final String TEST_SECRET = "test-jwt-secret-key-at-least-32-chars!!";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void generateAndVerifyValidToken() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-11-22T12:00:00Z"), ZoneOffset.UTC);
        JwtTokenProvider provider = new JwtTokenProvider(TEST_SECRET, 3600, objectMapper, fixedClock);

        String token = provider.generateToken("user-123", "demo@otelshop.local", "Demo User");
        assertThat(token.split("\\.")).hasSize(3);

        Optional<JwtTokenProvider.JwtClaims> claims = provider.verifyAndParse(token);
        assertThat(claims).isPresent();
        assertThat(claims.get().userId()).isEqualTo("user-123");
        assertThat(claims.get().email()).isEqualTo("demo@otelshop.local");
        assertThat(claims.get().fullName()).isEqualTo("Demo User");
    }

    @Test
    void rejectTamperedOrExpiredToken() {
        Clock issueClock = Clock.fixed(Instant.parse("2026-11-22T12:00:00Z"), ZoneOffset.UTC);
        Clock expiredClock = Clock.fixed(Instant.parse("2026-11-22T14:00:00Z"), ZoneOffset.UTC);

        JwtTokenProvider issuer = new JwtTokenProvider(TEST_SECRET, 3600, objectMapper, issueClock);
        String token = issuer.generateToken("user-123", "demo@otelshop.local", "Demo User");

        // Tampered signature
        assertThat(issuer.verifyAndParse(token + "x")).isEmpty();

        // Wrong secret
        JwtTokenProvider wrongSecret = new JwtTokenProvider("another-secret-key-at-least-32-chars!!", 3600, objectMapper, issueClock);
        assertThat(wrongSecret.verifyAndParse(token)).isEmpty();

        // Expired token
        JwtTokenProvider laterVerifier = new JwtTokenProvider(TEST_SECRET, 3600, objectMapper, expiredClock);
        assertThat(laterVerifier.verifyAndParse(token)).isEmpty();
    }

    @Test
    void rejectShortSecret() {
        assertThatThrownBy(() -> new JwtTokenProvider("short", 3600, objectMapper))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
