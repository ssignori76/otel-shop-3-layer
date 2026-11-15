package com.commerce.synthetic.merchant;

import com.commerce.synthetic.api.ApiErrorResponse;
import com.commerce.synthetic.api.PaymentAuthorizeRequest;
import com.commerce.synthetic.api.PaymentAuthorizeResponse;
import com.commerce.synthetic.scenario.CorrelationHeaders;
import com.commerce.synthetic.scenario.JourneyState;
import com.commerce.synthetic.scenario.ScenarioContext;
import com.commerce.synthetic.scenario.ScenarioName;
import com.commerce.synthetic.scenario.TelemetryHelper;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/internal/payments")
public class MerchantController {

    private static final Logger log = LoggerFactory.getLogger(MerchantController.class);

    private final String defaultScenario;
    private final boolean defaultScenarioEnabled;
    private final long merchantTimeoutMs;

    public MerchantController(
            @Value("${commerce.scenario.name:NORMAL}") String defaultScenario,
            @Value("${commerce.scenario.enabled:false}") boolean defaultScenarioEnabled,
            @Value("${commerce.scenario.merchant-timeout-ms:1500}") long merchantTimeoutMs
    ) {
        this.defaultScenario = defaultScenario;
        this.defaultScenarioEnabled = defaultScenarioEnabled;
        this.merchantTimeoutMs = merchantTimeoutMs;
    }

    @PostMapping("/authorize")
    public ResponseEntity<PaymentAuthorizeResponse> authorizePayment(
            @Valid @RequestBody PaymentAuthorizeRequest request,
            @RequestHeader(value = CorrelationHeaders.RUN_ID, required = false) String headerRunId,
            @RequestHeader(value = CorrelationHeaders.JOURNEY_ID, required = false) String headerJourneyId,
            @RequestHeader(value = CorrelationHeaders.SIMULATION_SCENARIO, required = false) String headerScenario,
            @RequestHeader(value = CorrelationHeaders.SIMULATION_ENABLED, required = false) String headerEnabled
    ) throws InterruptedException {
        long startNanos = System.nanoTime();
        String runId = headerRunId != null && !headerRunId.isBlank() ? headerRunId : request.runId();
        String journeyId = headerJourneyId != null && !headerJourneyId.isBlank() ? headerJourneyId : request.journeyId();
        ScenarioContext ctx = ScenarioContext.resolve(
                runId, journeyId, headerScenario, headerEnabled, defaultScenario, defaultScenarioEnabled
        );

        try {
            TelemetryHelper.bindContext(ctx, JourneyState.PAYMENT_AUTHORIZED);
            TelemetryHelper.bindEntityIds(request.cartId(), null, null);

            String statusLabel = "AUTHORIZED";
            if (ctx.isScenarioActive(ScenarioName.MERCHANT_TIMEOUT)) {
                statusLabel = "DELAYED";
                long sleepMs = merchantTimeoutMs + 1500L;
                TelemetryHelper.updateState(JourneyState.MERCHANT_TIMEOUT);
                log.warn("MERCHANT_TIMEOUT scenario active: delaying payment authorization by {} ms for cart_id={}", sleepMs, request.cartId());
                Thread.sleep(sleepMs);
            }

            String authId = "auth-" + UUID.randomUUID();
            Instant now = Instant.now();
            double durationMs = (System.nanoTime() - startNanos) / 1_000_000.0;

            TelemetryHelper.incrementCounter(
                    "commerce.payments.requests",
                    "{payment}",
                    "Total payment authorization requests in merchant-service",
                    Map.of("commerce.scenario", ctx.effectiveScenario().name(), "payment.status", statusLabel)
            );
            TelemetryHelper.recordHistogram(
                    "commerce.payment.authorization.duration",
                    "ms",
                    "Payment authorization processing duration in ms",
                    durationMs,
                    Map.of("commerce.scenario", ctx.effectiveScenario().name(), "payment.status", statusLabel)
            );

            log.info("Payment authorized: cart_id={} status={}", request.cartId(), statusLabel);
            return ResponseEntity.ok(new PaymentAuthorizeResponse(
                    authId,
                    journeyId,
                    request.cartId(),
                    JourneyState.PAYMENT_AUTHORIZED.name(),
                    now.toString()
            ));
        } finally {
            TelemetryHelper.clearMdc();
        }
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        TelemetryHelper.recordError(JourneyState.INTERNAL_FAILURE, "VALIDATION_ERROR", ex);
        log.warn("Payment request validation failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                ApiErrorResponse.of(
                        HttpStatus.BAD_REQUEST.value(),
                        "VALIDATION_ERROR",
                        "Invalid payment authorization payload",
                        "",
                        "",
                        TelemetryHelper.currentTraceId()
                )
        );
    }
}
