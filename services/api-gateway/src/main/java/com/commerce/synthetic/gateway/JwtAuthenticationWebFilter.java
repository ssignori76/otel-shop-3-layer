package com.commerce.synthetic.gateway;

import com.commerce.synthetic.api.ApiErrorResponse;
import com.commerce.synthetic.api.JwtTokenProvider;
import com.commerce.synthetic.scenario.CorrelationHeaders;
import com.commerce.synthetic.scenario.TelemetryHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

/**
 * Validates JWT Bearer tokens on protected API routes, strips spoofed identity headers,
 * and propagates verified x-user-id / x-user-email headers to downstream services.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class JwtAuthenticationWebFilter implements WebFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String STATIC_TEST_USER_ID = "00000000-0000-0000-0000-000000000001";
    private static final String STATIC_TEST_USER_EMAIL = "synthetic-test@otelshop.local";

    private final JwtTokenProvider jwtTokenProvider;
    private final ObjectMapper objectMapper;
    private final String staticTestToken;

    public JwtAuthenticationWebFilter(
            JwtTokenProvider jwtTokenProvider,
            ObjectMapper objectMapper,
            @Value("${commerce.auth.static-test-token:}") String staticTestToken
    ) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.objectMapper = objectMapper;
        this.staticTestToken = staticTestToken != null ? staticTestToken.trim() : "";
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (request.getMethod() == HttpMethod.OPTIONS) {
            return chain.filter(exchange);
        }

        String path = request.getURI().getPath();
        boolean requiresAuth = isProtectedPath(path);
        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        // Always strip caller-supplied identity headers to prevent spoofing
        ServerHttpRequest.Builder mutatedBuilder = request.mutate().headers(headers -> {
            headers.remove(CorrelationHeaders.USER_ID);
            headers.remove(CorrelationHeaders.USER_EMAIL);
        });

        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            String token = authHeader.substring(BEARER_PREFIX.length()).trim();
            Optional<JwtTokenProvider.JwtClaims> verified = verifyToken(token);
            if (verified.isEmpty()) {
                return unauthorized(exchange, "INVALID_TOKEN", "Invalid or expired JWT token");
            }
            JwtTokenProvider.JwtClaims claims = verified.get();
            mutatedBuilder.headers(headers -> {
                headers.set(CorrelationHeaders.USER_ID, claims.userId());
                headers.set(CorrelationHeaders.USER_EMAIL, claims.email());
            });
            return chain.filter(exchange.mutate().request(mutatedBuilder.build()).build());
        }

        if (requiresAuth) {
            return unauthorized(exchange, "UNAUTHORIZED", "Authentication required: provide Authorization Bearer token");
        }

        return chain.filter(exchange.mutate().request(mutatedBuilder.build()).build());
    }

    private Optional<JwtTokenProvider.JwtClaims> verifyToken(String token) {
        if (!staticTestToken.isEmpty() && constantTimeEquals(staticTestToken, token)) {
            return Optional.of(new JwtTokenProvider.JwtClaims(
                    STATIC_TEST_USER_ID, STATIC_TEST_USER_EMAIL, "Synthetic Test Token", 0L, Long.MAX_VALUE
            ));
        }
        return jwtTokenProvider.verifyAndParse(token);
    }

    private static boolean isProtectedPath(String path) {
        if (path == null) {
            return false;
        }
        return path.equals("/api/auth/me")
                || path.equals("/api/carts") || path.startsWith("/api/carts/")
                || path.equals("/api/checkouts") || path.startsWith("/api/checkouts/")
                || path.equals("/api/orders") || path.startsWith("/api/orders/")
                || path.equals("/api/fulfillments") || path.startsWith("/api/fulfillments/");
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8)
        );
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String errorCode, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String runId = exchange.getRequest().getHeaders().getFirst(CorrelationHeaders.RUN_ID);
        String journeyId = exchange.getRequest().getHeaders().getFirst(CorrelationHeaders.JOURNEY_ID);
        ApiErrorResponse error = ApiErrorResponse.of(
                HttpStatus.UNAUTHORIZED.value(),
                errorCode,
                message,
                runId != null ? runId : "",
                journeyId != null ? journeyId : "",
                TelemetryHelper.currentTraceId()
        );
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(error);
            DataBuffer buffer = response.bufferFactory().wrap(bytes);
            return response.writeWith(Mono.just(buffer));
        } catch (Exception ex) {
            return response.setComplete();
        }
    }
}
