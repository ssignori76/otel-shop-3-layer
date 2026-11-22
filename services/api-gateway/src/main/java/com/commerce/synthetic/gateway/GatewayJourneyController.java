package com.commerce.synthetic.gateway;

import com.commerce.synthetic.api.ApiErrorResponse;
import com.commerce.synthetic.api.CartResponse;
import com.commerce.synthetic.api.CheckoutRequest;
import com.commerce.synthetic.api.CheckoutResponse;
import com.commerce.synthetic.api.CreateCartRequest;
import com.commerce.synthetic.api.JourneyResponse;
import com.commerce.synthetic.api.JourneyStartRequest;
import com.commerce.synthetic.scenario.CorrelationHeaders;
import com.commerce.synthetic.scenario.JourneyState;
import com.commerce.synthetic.scenario.ScenarioContext;
import com.commerce.synthetic.scenario.TelemetryHelper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/journeys")
public class GatewayJourneyController {

    private static final Logger log = LoggerFactory.getLogger(GatewayJourneyController.class);

    private final WebClient cartWebClient;
    private final WebClient checkoutWebClient;
    private final String defaultScenario;
    private final boolean defaultScenarioEnabled;

    public GatewayJourneyController(
            @Qualifier("cartWebClient") WebClient cartWebClient,
            @Qualifier("checkoutWebClient") WebClient checkoutWebClient,
            @Value("${commerce.scenario.name:NORMAL}") String defaultScenario,
            @Value("${commerce.scenario.enabled:false}") boolean defaultScenarioEnabled
    ) {
        this.cartWebClient = cartWebClient;
        this.checkoutWebClient = checkoutWebClient;
        this.defaultScenario = defaultScenario;
        this.defaultScenarioEnabled = defaultScenarioEnabled;
    }

    @PostMapping
    public Mono<ResponseEntity<?>> executeJourney(
            @Valid @RequestBody JourneyStartRequest request,
            @RequestHeader HttpHeaders incomingHeaders
    ) {
        long startNanos = System.nanoTime();
        String runId = firstNonBlank(incomingHeaders.getFirst(CorrelationHeaders.RUN_ID), request.runId());
        String journeyId = firstNonBlank(incomingHeaders.getFirst(CorrelationHeaders.JOURNEY_ID), request.journeyId());
        String headerScenario = incomingHeaders.getFirst(CorrelationHeaders.SIMULATION_SCENARIO);
        String headerEnabled = incomingHeaders.getFirst(CorrelationHeaders.SIMULATION_ENABLED);

        ScenarioContext ctx = ScenarioContext.resolve(
                runId, journeyId, headerScenario, headerEnabled, defaultScenario, defaultScenarioEnabled
        );

        Span serverSpan = Span.current();
        try (Scope ignored = serverSpan.makeCurrent()) {
            TelemetryHelper.bindContext(ctx, JourneyState.PAGE_LOADED);
            log.info("Gateway received journey request: sku={} quantity={} state={}",
                    request.sku(), request.quantity(), JourneyState.PAGE_LOADED);
            TelemetryHelper.clearMdc();
        }

        CreateCartRequest createCartReq = new CreateCartRequest(
                ctx.runId(), ctx.journeyId(), request.sku(), Math.max(1, request.quantity())
        );

        // Step 1: POST /internal/carts -> Cart Service
        return cartWebClient.post()
                .uri("/internal/carts")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h -> propagateBusinessHeaders(h, incomingHeaders, ctx))
                .bodyValue(createCartReq)
                .retrieve()
                .bodyToMono(CartResponse.class)
                // Step 2: GET /internal/carts/{cartId} -> Cart Service
                .flatMap(createdCart -> {
                    try (Scope ignored = serverSpan.makeCurrent()) {
                        TelemetryHelper.bindContext(ctx, JourneyState.CART_CREATED);
                        TelemetryHelper.bindEntityIds(createdCart.cartId(), null, null);
                        TelemetryHelper.clearMdc();
                    }
                    return cartWebClient.get()
                            .uri("/internal/carts/{cartId}", createdCart.cartId())
                            .headers(h -> propagateBusinessHeaders(h, incomingHeaders, ctx))
                            .retrieve()
                            .bodyToMono(CartResponse.class);
                })
                // Step 3: POST /internal/checkouts -> Checkout Service
                .flatMap(readCart -> {
                    try (Scope ignored = serverSpan.makeCurrent()) {
                        TelemetryHelper.bindContext(ctx, JourneyState.CART_READ);
                        TelemetryHelper.bindEntityIds(readCart.cartId(), null, null);
                        TelemetryHelper.clearMdc();
                    }
                    CheckoutRequest checkoutReq = new CheckoutRequest(ctx.runId(), ctx.journeyId(), readCart.cartId());
                    return checkoutWebClient.post()
                            .uri("/internal/checkouts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .headers(h -> propagateBusinessHeaders(h, incomingHeaders, ctx))
                            .bodyValue(checkoutReq)
                            .retrieve()
                            .bodyToMono(CheckoutResponse.class);
                })
                .<ResponseEntity<?>>map(checkoutResp -> {
                    try (Scope ignored = serverSpan.makeCurrent()) {
                        TelemetryHelper.bindContext(ctx, JourneyState.ORDER_PUBLISHED);
                        TelemetryHelper.bindEntityIds(checkoutResp.cartId(), checkoutResp.orderId(), null);
                        double durationMs = (System.nanoTime() - startNanos) / 1_000_000.0;
                        TelemetryHelper.incrementCounter(
                                "commerce.gateway.requests",
                                "{request}",
                                "Total journey requests processed by api-gateway",
                                Map.of(
                                        "commerce.scenario", ctx.effectiveScenario().name(),
                                        "commerce.journey.state", JourneyState.ORDER_PUBLISHED.name(),
                                        "http.response.status_code", "202"
                                )
                        );
                        TelemetryHelper.recordHistogram(
                                "commerce.gateway.orchestration.duration",
                                "ms",
                                "Gateway journey orchestration duration in ms",
                                durationMs,
                                Map.of(
                                        "commerce.scenario", ctx.effectiveScenario().name(),
                                        "commerce.journey.state", JourneyState.ORDER_PUBLISHED.name()
                                )
                        );
                        String traceId = TelemetryHelper.currentTraceId();
                        log.info("Gateway completed journey orchestration: cart_id={} order_id={} state={}",
                                checkoutResp.cartId(), checkoutResp.orderId(), JourneyState.ORDER_PUBLISHED);
                        TelemetryHelper.clearMdc();

                        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new JourneyResponse(
                                ctx.runId(),
                                ctx.journeyId(),
                                checkoutResp.cartId(),
                                checkoutResp.orderId(),
                                checkoutResp.paymentAuthorizationId(),
                                JourneyState.ORDER_PUBLISHED.name(),
                                traceId.isBlank() ? checkoutResp.traceId() : traceId
                        ));
                    }
                })
                .onErrorResume(WebClientResponseException.class, upstreamEx -> {
                    try (Scope ignored = serverSpan.makeCurrent()) {
                        int statusCode = upstreamEx.getStatusCode().value();
                        ApiErrorResponse upstreamError = null;
                        try {
                            upstreamError = upstreamEx.getResponseBodyAs(ApiErrorResponse.class);
                        } catch (Exception ignoredParse) {
                            // Fallback if upstream body is not an ApiErrorResponse
                        }
                        String errorCode = upstreamError != null && upstreamError.errorCode() != null
                                ? upstreamError.errorCode()
                                : "UPSTREAM_ERROR";
                        String message = upstreamError != null && upstreamError.message() != null
                                ? upstreamError.message()
                                : "Upstream service returned HTTP " + statusCode;
                        JourneyState terminalState = mapErrorCodeToState(errorCode);

                        TelemetryHelper.bindContext(ctx, terminalState);
                        TelemetryHelper.recordError(terminalState, errorCode, upstreamEx);
                        double durationMs = (System.nanoTime() - startNanos) / 1_000_000.0;
                        TelemetryHelper.incrementCounter(
                                "commerce.gateway.requests",
                                "{request}",
                                "Total journey requests processed by api-gateway",
                                Map.of(
                                        "commerce.scenario", ctx.effectiveScenario().name(),
                                        "commerce.journey.state", terminalState.name(),
                                        "http.response.status_code", Integer.toString(statusCode)
                                )
                        );
                        TelemetryHelper.recordHistogram(
                                "commerce.gateway.orchestration.duration",
                                "ms",
                                "Gateway journey orchestration duration in ms",
                                durationMs,
                                Map.of(
                                        "commerce.scenario", ctx.effectiveScenario().name(),
                                        "commerce.journey.state", terminalState.name()
                                )
                        );
                        String traceId = TelemetryHelper.currentTraceId();
                        log.error("Gateway propagating upstream error: status={} error_code={} state={}",
                                statusCode, errorCode, terminalState);
                        TelemetryHelper.clearMdc();

                        ApiErrorResponse body = ApiErrorResponse.of(
                                statusCode,
                                errorCode,
                                message,
                                ctx.runId(),
                                ctx.journeyId(),
                                traceId
                        );
                        return Mono.just(ResponseEntity.status(statusCode).body(body));
                    }
                })
                .onErrorResume(ex -> {
                    try (Scope ignored = serverSpan.makeCurrent()) {
                        TelemetryHelper.bindContext(ctx, JourneyState.INTERNAL_FAILURE);
                        TelemetryHelper.recordError(JourneyState.INTERNAL_FAILURE, "INTERNAL_FAILURE", ex);
                        TelemetryHelper.incrementCounter(
                                "commerce.gateway.requests",
                                "{request}",
                                "Total journey requests processed by api-gateway",
                                Map.of(
                                        "commerce.scenario", ctx.effectiveScenario().name(),
                                        "commerce.journey.state", JourneyState.INTERNAL_FAILURE.name(),
                                        "http.response.status_code", "500"
                                )
                        );
                        String traceId = TelemetryHelper.currentTraceId();
                        log.error("Gateway encountered unexpected orchestration failure", ex);
                        TelemetryHelper.clearMdc();

                        return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                                ApiErrorResponse.of(
                                        HttpStatus.INTERNAL_SERVER_ERROR.value(),
                                        "INTERNAL_FAILURE",
                                        "Gateway orchestration failure",
                                        ctx.runId(),
                                        ctx.journeyId(),
                                        traceId
                                )
                        ));
                    }
                });
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(WebExchangeBindException ex) {
        TelemetryHelper.recordError(JourneyState.INTERNAL_FAILURE, "VALIDATION_ERROR", ex);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                ApiErrorResponse.of(
                        HttpStatus.BAD_REQUEST.value(),
                        "VALIDATION_ERROR",
                        "Invalid journey request payload",
                        "",
                        "",
                        TelemetryHelper.currentTraceId()
                )
        );
    }

    private static void propagateBusinessHeaders(HttpHeaders target, HttpHeaders incoming, ScenarioContext ctx) {
        target.set(CorrelationHeaders.RUN_ID, ctx.runId());
        target.set(CorrelationHeaders.JOURNEY_ID, ctx.journeyId());
        target.set(CorrelationHeaders.SIMULATION_SCENARIO, ctx.scenarioName().name());
        target.set(CorrelationHeaders.SIMULATION_ENABLED, Boolean.toString(ctx.scenarioEnabled()));

        String tracestate = incoming.getFirst(CorrelationHeaders.TRACESTATE);
        if (tracestate != null && !tracestate.isBlank()) {
            target.set(CorrelationHeaders.TRACESTATE, tracestate);
        }
        String baggage = incoming.getFirst(CorrelationHeaders.BAGGAGE);
        if (baggage != null && !baggage.isBlank()) {
            target.set(CorrelationHeaders.BAGGAGE, baggage);
        }
        String userId = incoming.getFirst(CorrelationHeaders.USER_ID);
        if (userId != null && !userId.isBlank()) {
            target.set(CorrelationHeaders.USER_ID, userId);
        }
        String userEmail = incoming.getFirst(CorrelationHeaders.USER_EMAIL);
        if (userEmail != null && !userEmail.isBlank()) {
            target.set(CorrelationHeaders.USER_EMAIL, userEmail);
        }
    }

    private static JourneyState mapErrorCodeToState(String errorCode) {
        if (errorCode == null) {
            return JourneyState.INTERNAL_FAILURE;
        }
        return switch (errorCode) {
            case "BE_ERROR" -> JourneyState.CHECKOUT_FAILED;
            case "DB_TIMEOUT" -> JourneyState.DB_TIMEOUT;
            case "MERCHANT_TIMEOUT" -> JourneyState.MERCHANT_TIMEOUT;
            case "BROKER_UNAVAILABLE" -> JourneyState.PAYMENT_AUTHORIZED_NOT_PUBLISHED;
            default -> JourneyState.INTERNAL_FAILURE;
        };
    }

    private static String firstNonBlank(String first, String fallback) {
        return (first != null && !first.isBlank()) ? first : (fallback != null ? fallback : "");
    }
}
