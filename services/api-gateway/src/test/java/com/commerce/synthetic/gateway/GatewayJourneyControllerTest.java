package com.commerce.synthetic.gateway;

import com.commerce.synthetic.api.ApiErrorResponse;
import com.commerce.synthetic.api.CartResponse;
import com.commerce.synthetic.api.CheckoutResponse;
import com.commerce.synthetic.api.JourneyStartRequest;
import com.commerce.synthetic.scenario.CorrelationHeaders;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

@SpringBootTest(properties = {
        "spring.main.allow-bean-definition-overriding=true"
})
@AutoConfigureWebTestClient
class GatewayJourneyControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @TestConfiguration
    static class StubWebClientConfig {

        private final ObjectMapper mapper = new ObjectMapper();

        @Bean("cartWebClient")
        @Primary
        public WebClient stubCartWebClient() {
            ExchangeFunction exchange = request -> {
                try {
                    String path = request.url().getPath();
                    String journeyId = request.headers().getFirst(CorrelationHeaders.JOURNEY_ID);
                    if (path.equals("/internal/carts")) {
                        CartResponse created = new CartResponse(
                                "11111111-2222-3333-4444-555555555555",
                                journeyId != null ? journeyId : UUID.randomUUID().toString(),
                                "CART_CREATED",
                                Instant.now().toString(),
                                Instant.now().toString()
                        );
                        return Mono.just(ClientResponse.create(HttpStatus.CREATED)
                                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                                .body(mapper.writeValueAsString(created))
                                .build());
                    }
                    CartResponse read = new CartResponse(
                            "11111111-2222-3333-4444-555555555555",
                            journeyId != null ? journeyId : UUID.randomUUID().toString(),
                            "CART_READ",
                            Instant.now().toString(),
                            Instant.now().toString()
                    );
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                            .body(mapper.writeValueAsString(read))
                            .build());
                } catch (Exception e) {
                    return Mono.error(e);
                }
            };
            return WebClient.builder().baseUrl("http://cart-service:8081").exchangeFunction(exchange).build();
        }

        @Bean("checkoutWebClient")
        @Primary
        public WebClient stubCheckoutWebClient() {
            ExchangeFunction exchange = request -> {
                try {
                    String scenario = request.headers().getFirst(CorrelationHeaders.SIMULATION_SCENARIO);
                    String enabled = request.headers().getFirst(CorrelationHeaders.SIMULATION_ENABLED);
                    String runId = request.headers().getFirst(CorrelationHeaders.RUN_ID);
                    String journeyId = request.headers().getFirst(CorrelationHeaders.JOURNEY_ID);

                    if ("true".equalsIgnoreCase(enabled) && "BE_ERROR".equalsIgnoreCase(scenario)) {
                        ApiErrorResponse err = ApiErrorResponse.of(
                                500, "BE_ERROR", "Synthetic backend error", runId, journeyId, "trace-123"
                        );
                        return Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR)
                                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                                .body(mapper.writeValueAsString(err))
                                .build());
                    }

                    CheckoutResponse ok = new CheckoutResponse(
                            "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                            journeyId != null ? journeyId : UUID.randomUUID().toString(),
                            "11111111-2222-3333-4444-555555555555",
                            "auth-999",
                            "PUBLISHED",
                            "trace-123"
                    );
                    return Mono.just(ClientResponse.create(HttpStatus.ACCEPTED)
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                            .body(mapper.writeValueAsString(ok))
                            .build());
                } catch (Exception e) {
                    return Mono.error(e);
                }
            };
            return WebClient.builder().baseUrl("http://checkout-service:8082").exchangeFunction(exchange).build();
        }
    }

    @Test
    void orchestratesNormalJourneyAndReturns202() {
        String runId = UUID.randomUUID().toString();
        String journeyId = UUID.randomUUID().toString();
        JourneyStartRequest req = new JourneyStartRequest(runId, journeyId, "SKU-001", 1);

        webTestClient.post()
                .uri("/api/journeys")
                .contentType(MediaType.APPLICATION_JSON)
                .header(CorrelationHeaders.RUN_ID, runId)
                .header(CorrelationHeaders.JOURNEY_ID, journeyId)
                .header(CorrelationHeaders.SIMULATION_SCENARIO, "NORMAL")
                .header(CorrelationHeaders.SIMULATION_ENABLED, "false")
                .bodyValue(req)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.state").isEqualTo("ORDER_PUBLISHED")
                .jsonPath("$.cart_id").isEqualTo("11111111-2222-3333-4444-555555555555")
                .jsonPath("$.order_id").isEqualTo("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    }

    @Test
    void propagatesUpstreamFailureStatusWithoutConversion() {
        String runId = UUID.randomUUID().toString();
        String journeyId = UUID.randomUUID().toString();
        JourneyStartRequest req = new JourneyStartRequest(runId, journeyId, "SKU-001", 1);

        webTestClient.post()
                .uri("/api/journeys")
                .contentType(MediaType.APPLICATION_JSON)
                .header(CorrelationHeaders.RUN_ID, runId)
                .header(CorrelationHeaders.JOURNEY_ID, journeyId)
                .header(CorrelationHeaders.SIMULATION_SCENARIO, "BE_ERROR")
                .header(CorrelationHeaders.SIMULATION_ENABLED, "true")
                .bodyValue(req)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
                .expectBody()
                .jsonPath("$.error_code").isEqualTo("BE_ERROR");
    }
}
