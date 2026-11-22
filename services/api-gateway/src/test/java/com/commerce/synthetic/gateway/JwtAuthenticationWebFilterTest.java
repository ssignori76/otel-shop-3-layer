package com.commerce.synthetic.gateway;

import com.commerce.synthetic.api.JwtTokenProvider;
import com.commerce.synthetic.scenario.CorrelationHeaders;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class JwtAuthenticationWebFilterTest {

    private static final String SECRET = "gateway-unit-test-secret-key-32-chars!!";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JwtTokenProvider jwtProvider = new JwtTokenProvider(SECRET, 3600, objectMapper);

    @Test
    void rejectsProtectedRouteWithoutToken() {
        JwtAuthenticationWebFilter filter = new JwtAuthenticationWebFilter(jwtProvider, objectMapper, "");
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/carts/active"));

        filter.filter(exchange, ex -> Mono.empty()).block();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void allowsProtectedRouteWithValidJwtAndPropagatesUserHeaders() {
        JwtAuthenticationWebFilter filter = new JwtAuthenticationWebFilter(jwtProvider, objectMapper, "");
        String token = jwtProvider.generateToken("user-abc", "alice@example.com", "Alice");

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(CorrelationHeaders.USER_ID, "spoofed-id")
        );

        AtomicReference<String> forwardedUserId = new AtomicReference<>();
        AtomicReference<String> forwardedEmail = new AtomicReference<>();
        WebFilterChain chain = ex -> {
            forwardedUserId.set(ex.getRequest().getHeaders().getFirst(CorrelationHeaders.USER_ID));
            forwardedEmail.set(ex.getRequest().getHeaders().getFirst(CorrelationHeaders.USER_EMAIL));
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(forwardedUserId.get()).isEqualTo("user-abc");
        assertThat(forwardedEmail.get()).isEqualTo("alice@example.com");
    }

    @Test
    void allowsProtectedRouteWithConfiguredStaticTestToken() {
        JwtAuthenticationWebFilter filter = new JwtAuthenticationWebFilter(jwtProvider, objectMapper, "static-test-token-123");
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/carts/active")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer static-test-token-123")
        );

        AtomicReference<String> forwardedEmail = new AtomicReference<>();
        filter.filter(exchange, ex -> {
            forwardedEmail.set(ex.getRequest().getHeaders().getFirst(CorrelationHeaders.USER_EMAIL));
            return Mono.empty();
        }).block();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(forwardedEmail.get()).isEqualTo("synthetic-test@otelshop.local");
    }

    @Test
    void allowsPublicProductsRouteWithoutTokenAndStripsSpoofedHeaders() {
        JwtAuthenticationWebFilter filter = new JwtAuthenticationWebFilter(jwtProvider, objectMapper, "");
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/products")
                        .header(CorrelationHeaders.USER_ID, "spoofed")
        );

        AtomicReference<String> forwardedUserId = new AtomicReference<>("initial");
        filter.filter(exchange, ex -> {
            forwardedUserId.set(ex.getRequest().getHeaders().getFirst(CorrelationHeaders.USER_ID));
            return Mono.empty();
        }).block();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(forwardedUserId.get()).isNull();
    }
}
