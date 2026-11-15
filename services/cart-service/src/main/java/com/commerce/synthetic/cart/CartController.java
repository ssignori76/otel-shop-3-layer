package com.commerce.synthetic.cart;

import com.commerce.synthetic.api.ApiErrorResponse;
import com.commerce.synthetic.api.CartResponse;
import com.commerce.synthetic.api.CreateCartRequest;
import com.commerce.synthetic.scenario.CorrelationHeaders;
import com.commerce.synthetic.scenario.JourneyState;
import com.commerce.synthetic.scenario.ScenarioContext;
import com.commerce.synthetic.scenario.TelemetryHelper;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/internal/carts")
public class CartController {

    private static final Logger log = LoggerFactory.getLogger(CartController.class);

    private final CartRepository cartRepository;
    private final String defaultScenario;
    private final boolean defaultScenarioEnabled;

    public CartController(
            CartRepository cartRepository,
            @Value("${commerce.scenario.name:NORMAL}") String defaultScenario,
            @Value("${commerce.scenario.enabled:false}") boolean defaultScenarioEnabled
    ) {
        this.cartRepository = cartRepository;
        this.defaultScenario = defaultScenario;
        this.defaultScenarioEnabled = defaultScenarioEnabled;
    }

    @PostMapping
    public ResponseEntity<CartResponse> createCart(
            @Valid @RequestBody CreateCartRequest request,
            @RequestHeader(value = CorrelationHeaders.RUN_ID, required = false) String headerRunId,
            @RequestHeader(value = CorrelationHeaders.JOURNEY_ID, required = false) String headerJourneyId,
            @RequestHeader(value = CorrelationHeaders.SIMULATION_SCENARIO, required = false) String headerScenario,
            @RequestHeader(value = CorrelationHeaders.SIMULATION_ENABLED, required = false) String headerEnabled
    ) {
        long startNanos = System.nanoTime();
        String runId = headerRunId != null && !headerRunId.isBlank() ? headerRunId : request.runId();
        String journeyId = headerJourneyId != null && !headerJourneyId.isBlank() ? headerJourneyId : request.journeyId();
        ScenarioContext ctx = ScenarioContext.resolve(
                runId, journeyId, headerScenario, headerEnabled, defaultScenario, defaultScenarioEnabled
        );

        try {
            TelemetryHelper.bindContext(ctx, JourneyState.CART_CREATED);
            CartResponse created = cartRepository.createCart(request);
            TelemetryHelper.bindEntityIds(created.cartId(), null, null);

            double durationMs = (System.nanoTime() - startNanos) / 1_000_000.0;
            TelemetryHelper.incrementCounter(
                    "commerce.carts.created",
                    "{cart}",
                    "Total carts created in cart-service",
                    Map.of("commerce.scenario", ctx.effectiveScenario().name())
            );
            TelemetryHelper.recordHistogram(
                    "commerce.cart.operation.duration",
                    "ms",
                    "Cart database operation duration in ms",
                    durationMs,
                    Map.of("commerce.operation", "create", "commerce.scenario", ctx.effectiveScenario().name())
            );

            log.info("Cart created successfully: cart_id={} state={}", created.cartId(), JourneyState.CART_CREATED);
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } finally {
            TelemetryHelper.clearMdc();
        }
    }

    @GetMapping("/{cartId}")
    public ResponseEntity<?> getCart(
            @PathVariable("cartId") String cartId,
            @RequestHeader(value = CorrelationHeaders.RUN_ID, required = false, defaultValue = "") String runId,
            @RequestHeader(value = CorrelationHeaders.JOURNEY_ID, required = false, defaultValue = "") String journeyId,
            @RequestHeader(value = CorrelationHeaders.SIMULATION_SCENARIO, required = false) String headerScenario,
            @RequestHeader(value = CorrelationHeaders.SIMULATION_ENABLED, required = false) String headerEnabled
    ) {
        long startNanos = System.nanoTime();
        ScenarioContext ctx = ScenarioContext.resolve(
                runId, journeyId, headerScenario, headerEnabled, defaultScenario, defaultScenarioEnabled
        );

        try {
            TelemetryHelper.bindContext(ctx, JourneyState.CART_READ);
            TelemetryHelper.bindEntityIds(cartId, null, null);

            Optional<CartResponse> found = cartRepository.readCart(cartId);
            if (found.isEmpty()) {
                TelemetryHelper.recordError(JourneyState.INTERNAL_FAILURE, "CART_NOT_FOUND", null);
                log.warn("Cart not found: cart_id={}", cartId);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                        ApiErrorResponse.of(
                                HttpStatus.NOT_FOUND.value(),
                                "CART_NOT_FOUND",
                                "Requested cart was not found",
                                runId,
                                journeyId,
                                TelemetryHelper.currentTraceId()
                        )
                );
            }

            CartResponse cart = found.get();
            double durationMs = (System.nanoTime() - startNanos) / 1_000_000.0;
            TelemetryHelper.incrementCounter(
                    "commerce.carts.read",
                    "{cart}",
                    "Total carts retrieved in cart-service",
                    Map.of("commerce.scenario", ctx.effectiveScenario().name())
            );
            TelemetryHelper.recordHistogram(
                    "commerce.cart.operation.duration",
                    "ms",
                    "Cart database operation duration in ms",
                    durationMs,
                    Map.of("commerce.operation", "read", "commerce.scenario", ctx.effectiveScenario().name())
            );

            log.info("Cart retrieved successfully: cart_id={} state={}", cart.cartId(), JourneyState.CART_READ);
            return ResponseEntity.ok(cart);
        } finally {
            TelemetryHelper.clearMdc();
        }
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        TelemetryHelper.recordError(JourneyState.INTERNAL_FAILURE, "VALIDATION_ERROR", ex);
        log.warn("Cart request validation failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                ApiErrorResponse.of(
                        HttpStatus.BAD_REQUEST.value(),
                        "VALIDATION_ERROR",
                        "Invalid cart request payload",
                        "",
                        "",
                        TelemetryHelper.currentTraceId()
                )
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex) {
        TelemetryHelper.recordError(JourneyState.INTERNAL_FAILURE, "INTERNAL_FAILURE", ex);
        log.error("Unexpected cart service error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiErrorResponse.of(
                        HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        "INTERNAL_FAILURE",
                        "Internal cart service error",
                        "",
                        "",
                        TelemetryHelper.currentTraceId()
                )
        );
    }
}
