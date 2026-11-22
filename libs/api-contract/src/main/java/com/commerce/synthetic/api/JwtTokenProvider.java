package com.commerce.synthetic.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Lightweight, zero-external-dependency RFC 7519 HS256 JWT provider
 * shared by auth-service (issuing) and api-gateway (verification).
 */
public class JwtTokenProvider {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String HEADER_JSON = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final byte[] secretBytes;
    private final long expirationSeconds;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public record JwtClaims(String userId, String email, String fullName, long issuedAt, long expiresAt) {}

    public JwtTokenProvider(String secret, long expirationSeconds, ObjectMapper objectMapper) {
        this(secret, expirationSeconds, objectMapper, Clock.systemUTC());
    }

    public JwtTokenProvider(String secret, long expirationSeconds, ObjectMapper objectMapper, Clock clock) {
        if (secret == null || secret.trim().length() < 16) {
            throw new IllegalArgumentException("JWT secret must be at least 16 characters");
        }
        this.secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        this.expirationSeconds = expirationSeconds > 0 ? expirationSeconds : 86400L;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public long getExpirationSeconds() {
        return expirationSeconds;
    }

    public String generateToken(String userId, String email, String fullName) {
        try {
            long now = Instant.now(clock).getEpochSecond();
            long exp = now + expirationSeconds;

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sub", userId);
            payload.put("email", email);
            payload.put("name", fullName);
            payload.put("iat", now);
            payload.put("exp", exp);

            String encodedHeader = URL_ENCODER.encodeToString(HEADER_JSON.getBytes(StandardCharsets.UTF_8));
            String encodedPayload = URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(payload));
            String signingInput = encodedHeader + "." + encodedPayload;
            String signature = URL_ENCODER.encodeToString(hmacSha256(signingInput));
            return signingInput + "." + signature;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to generate JWT token", ex);
        }
    }

    public Optional<JwtClaims> verifyAndParse(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String[] parts = token.trim().split("\\.");
        if (parts.length != 3) {
            return Optional.empty();
        }

        try {
            String signingInput = parts[0] + "." + parts[1];
            byte[] expectedSig = hmacSha256(signingInput);
            byte[] providedSig = URL_DECODER.decode(parts[2]);
            if (!MessageDigest.isEqual(expectedSig, providedSig)) {
                return Optional.empty();
            }

            byte[] headerBytes = URL_DECODER.decode(parts[0]);
            Map<String, Object> header = objectMapper.readValue(headerBytes, MAP_TYPE);
            if (!"HS256".equals(header.get("alg"))) {
                return Optional.empty();
            }

            byte[] payloadBytes = URL_DECODER.decode(parts[1]);
            Map<String, Object> payload = objectMapper.readValue(payloadBytes, MAP_TYPE);

            String sub = stringClaim(payload.get("sub"));
            String email = stringClaim(payload.get("email"));
            String name = stringClaim(payload.get("name"));
            long iat = longClaim(payload.get("iat"));
            long exp = longClaim(payload.get("exp"));

            long now = Instant.now(clock).getEpochSecond();
            if (sub == null || email == null || exp <= now) {
                return Optional.empty();
            }
            return Optional.of(new JwtClaims(sub, email, name == null ? "" : name, iat, exp));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private byte[] hmacSha256(String data) throws Exception {
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(secretBytes, HMAC_ALGORITHM));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String stringClaim(Object value) {
        if (value == null) {
            return null;
        }
        String s = value.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static long longClaim(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s) {
            return Long.parseLong(s);
        }
        return 0L;
    }
}
