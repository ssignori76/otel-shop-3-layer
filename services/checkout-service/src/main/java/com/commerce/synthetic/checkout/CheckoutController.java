package com.commerce.synthetic.checkout;

import com.commerce.synthetic.api.ApiErrorResponse;
import com.commerce.synthetic.api.CheckoutRequest;
import com.commerce.synthetic.api.CheckoutResponse;
import com.commerce.synthetic.api.PaymentAuthorizeRequest;
import com.commerce.synthetic.api.PaymentAuthorizeResponse;
import com.commerce.synthetic.messaging.OrderCreatedMessage;
import com.commerce.synthetic.messaging.RabbitTopologyConstants;
import com.commerce.synthetic.scenario.CorrelationHeaders;
import com.commerce.synthetic.scenario.JourneyState;
import com.commerce.synthetic.scenario.ScenarioContext;
import com.commerce.synthetic.scenario.ScenarioException;
import com.commerce.synthetic.scenario.ScenarioName;
import com.commerce.synthetic.scenario.TelemetryHelper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/internal/checkouts")
public class CheckoutController {

    private static final Logger log = LoggerFactory.getLogger(CheckoutController.class);

    private final OrderRepository orderRepository;
    private final OrderPublisher orderPublisher;
    private final RestClient merchantRestClient;
    private final String defaultScenario;
    private final boolean defaultScenarioEnabled;
    private final long dbSlowDurationMs;
    private final int dbOperationTimeoutMs;

    public CheckoutController(
            OrderRepository orderRepository,
            OrderPublisher orderPublisher,
            RestClient merchantRestClient,
            @Value("${commerce.scenario.name:NORMAL}") String defaultScenario,
            @Value("${commerce.scenario.enabled:false}") boolean defaultScenarioEnabled,
            @Value("${commerce.scenario.db-slow-duration-ms:1500}") long dbSlowDurationMs,
            @Value("${commerce.scenario.db-operation-timeout-ms:1000}") int dbOperationTimeoutMs
    ) {
        this.orderRepository = orderRepository;
        this.orderPublisher = orderPublisher;
        this.merchantRestClient = merchantRestClient;
        this.defaultScenario = defaultScenario;
        this.defaultScenarioEnabled = defaultScenarioEnabled;
        this.dbSlowDurationMs = dbSlowDurationMs;
        this.dbOperationTimeoutMs = dbOperationTimeoutMs;
    }

    @PostMapping
    public ResponseEntity<CheckoutResponse> processCheckout(
            @Valid @RequestBody CheckoutRequest request,
            @RequestHeader(value = CorrelationHeaders.RUN_ID, required = false) String headerRunId,
            @RequestHeader(value = CorrelationHeaders.JOURNEY_ID, required = false) String headerJourneyId,
            @RequestHeader(value = CorrelationHeaders.SIMULATION_SCENARIO, required = false) String headerScenario,
            @RequestHeader(value = CorrelationHeaders.SIMULATION_ENABLED, required = false) String headerEnabled,
            @RequestHeader(value = CorrelationHeaders.USER_ID, required = false) String userId
    ) {
        long startNanos = System.nanoTime();
        String runId = headerRunId != null && !headerRunId.isBlank() ? headerRunId : request.runId();
        String journeyId = headerJourneyId != null && !headerJourneyId.isBlank() ? headerJourneyId : request.journeyId();
        ScenarioContext ctx = ScenarioContext.resolve(
                runId, journeyId, headerScenario, headerEnabled, defaultScenario, defaultScenarioEnabled
        );

        try {
            TelemetryHelper.bindContext(ctx, JourneyState.CART_READ);
            TelemetryHelper.bindEntityIds(request.cartId(), null, null);
            TelemetryHelper.incrementCounter(
                    "commerce.checkouts.started",
                    "{checkout}",
                    "Total checkouts started in checkout-service",
                    Map.of("commerce.scenario", ctx.effectiveScenario().name())
            );

            // 1. Check BE_ERROR before DB read (Section 14)
            if (ctx.isScenarioActive(ScenarioName.BE_ERROR)) {
                throw new ScenarioException(
                        ScenarioName.BE_ERROR,
                        JourneyState.CHECKOUT_FAILED,
                        HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        "BE_ERROR",
                        "Synthetic backend error injected before database read"
                );
            }

            // 2. Execute DB operation (supports normal, DB_SLOW, and real JDBC DB_TIMEOUT)
            try {
                orderRepository.verifyDatabaseAndApplyDbScenario(
                        ctx.isScenarioActive(ScenarioName.DB_SLOW),
                        dbSlowDurationMs,
                        ctx.isScenarioActive(ScenarioName.DB_TIMEOUT),
                        dbOperationTimeoutMs
                );
            } catch (DataAccessException | SQLException dbEx) {
                throw new ScenarioException(
                        ScenarioName.DB_TIMEOUT,
                        JourneyState.DB_TIMEOUT,
                        HttpStatus.GATEWAY_TIMEOUT.value(),
                        "DB_TIMEOUT",
                        "Checkout database operation timed out",
                        dbEx
                );
            }

            // 3. Call Merchant Service for payment authorization
            PaymentAuthorizeResponse paymentResponse;
            try {
                paymentResponse = merchantRestClient.post()
                        .uri("/internal/payments/authorize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(CorrelationHeaders.RUN_ID, ctx.runId())
                        .header(CorrelationHeaders.JOURNEY_ID, ctx.journeyId())
                        .header(CorrelationHeaders.SIMULATION_SCENARIO, ctx.scenarioName().name())
                        .header(CorrelationHeaders.SIMULATION_ENABLED, Boolean.toString(ctx.scenarioEnabled()))
                        .body(new PaymentAuthorizeRequest(ctx.runId(), ctx.journeyId(), request.cartId()))
                        .retrieve()
                        .body(PaymentAuthorizeResponse.class);
            } catch (RestClientException timeoutEx) {
                throw new ScenarioException(
                        ScenarioName.MERCHANT_TIMEOUT,
                        JourneyState.MERCHANT_TIMEOUT,
                        HttpStatus.GATEWAY_TIMEOUT.value(),
                        "MERCHANT_TIMEOUT",
                        "Merchant payment authorization timed out",
                        timeoutEx
                );
            }

            if (paymentResponse == null || paymentResponse.paymentAuthorizationId() == null) {
                throw new IllegalStateException("Empty payment authorization response from merchant-service");
            }
            TelemetryHelper.updateState(JourneyState.PAYMENT_AUTHORIZED);

            // 4. Persist order in checkout_db.orders
            String orderId = UUID.randomUUID().toString();
            TelemetryHelper.bindEntityIds(request.cartId(), orderId, null);
            String normalizedUserId = (userId != null && !userId.isBlank()) ? userId.trim() : null;
            int totalCents = request.totalCents() != null ? request.totalCents() : 0;
            orderRepository.insertOrder(
                    orderId,
                    ctx.journeyId(),
                    request.cartId(),
                    paymentResponse.paymentAuthorizationId(),
                    JourneyState.PAYMENT_AUTHORIZED.name(),
                    normalizedUserId,
                    totalCents
            );

            // 5. Publish order message to RabbitMQ using publisher confirms
            OrderCreatedMessage orderMessage = new OrderCreatedMessage(
                    RabbitTopologyConstants.SCHEMA_VERSION_V1,
                    UUID.randomUUID().toString(),
                    ctx.runId(),
                    ctx.journeyId(),
                    orderId,
                    request.cartId(),
                    paymentResponse.paymentAuthorizationId(),
                    Instant.now().toString(),
                    ctx.scenarioName().name(),
                    ctx.scenarioEnabled()
            );

            try {
                orderPublisher.publishOrderWithConfirm(orderMessage, ctx);
            } catch (AmqpException amqpEx) {
                orderRepository.updateOrderStatus(orderId, JourneyState.PAYMENT_AUTHORIZED_NOT_PUBLISHED.name());
                throw new ScenarioException(
                        ScenarioName.BROKER_UNAVAILABLE,
                        JourneyState.PAYMENT_AUTHORIZED_NOT_PUBLISHED,
                        HttpStatus.SERVICE_UNAVAILABLE.value(),
                        "BROKER_UNAVAILABLE",
                        "Order persistence succeeded but RabbitMQ publish confirmation failed",
                        amqpEx
                );
            }

            // 6. Update order state to PUBLISHED after positive confirm (Section 7.1 Step 12)
            orderRepository.updateOrderStatus(orderId, "PUBLISHED");
            TelemetryHelper.updateState(JourneyState.ORDER_PUBLISHED);

            double durationMs = (System.nanoTime() - startNanos) / 1_000_000.0;
            TelemetryHelper.incrementCounter(
                    "commerce.orders.published",
                    "{order}",
                    "Total orders published to RabbitMQ with positive confirm",
                    Map.of("commerce.scenario", ctx.effectiveScenario().name())
            );
            TelemetryHelper.incrementCounter(
                    "commerce.checkouts.completed",
                    "{checkout}",
                    "Total checkouts completed successfully",
                    Map.of(
                            "commerce.scenario", ctx.effectiveScenario().name(),
                            "commerce.journey.state", JourneyState.ORDER_PUBLISHED.name()
                    )
            );
            TelemetryHelper.recordHistogram(
                    "commerce.checkout.duration",
                    "ms",
                    "Checkout orchestration duration in ms",
                    durationMs,
                    Map.of(
                            "commerce.scenario", ctx.effectiveScenario().name(),
                            "commerce.journey.state", JourneyState.ORDER_PUBLISHED.name()
                    )
            );

            log.info("Checkout completed and order published: order_id={} cart_id={} state={}",
                    orderId, request.cartId(), JourneyState.ORDER_PUBLISHED);

            return ResponseEntity.status(HttpStatus.ACCEPTED).body(new CheckoutResponse(
                    orderId,
                    ctx.journeyId(),
                    request.cartId(),
                    paymentResponse.paymentAuthorizationId(),
                    "PUBLISHED",
                    TelemetryHelper.currentTraceId()
            ));
        } catch (ScenarioException scenarioEx) {
            double durationMs = (System.nanoTime() - startNanos) / 1_000_000.0;
            TelemetryHelper.recordError(scenarioEx.getTerminalState(), scenarioEx.getErrorCode(), scenarioEx);
            TelemetryHelper.incrementCounter(
                    "commerce.checkouts.failed",
                    "{checkout}",
                    "Total checkouts failed in checkout-service",
                    Map.of(
                            "commerce.scenario", ctx.effectiveScenario().name(),
                            "commerce.journey.state", scenarioEx.getTerminalState().name(),
                            "error.code", scenarioEx.getErrorCode()
                    )
            );
            TelemetryHelper.recordHistogram(
                    "commerce.checkout.duration",
                    "ms",
                    "Checkout orchestration duration in ms",
                    durationMs,
                    Map.of(
                            "commerce.scenario", ctx.effectiveScenario().name(),
                            "commerce.journey.state", scenarioEx.getTerminalState().name()
                    )
            );
            log.error("Checkout failed due to scenario {}: state={} message={}",
                    scenarioEx.getErrorCode(), scenarioEx.getTerminalState(), scenarioEx.getMessage());
            throw scenarioEx;
        } finally {
            TelemetryHelper.clearMdc();
        }
    }

    @ExceptionHandler(ScenarioException.class)
    public ResponseEntity<ApiErrorResponse> handleScenarioException(
            ScenarioException ex,
            HttpServletRequest request
    ) {
        try {
            String runId = request.getHeader(CorrelationHeaders.RUN_ID);
            String journeyId = request.getHeader(CorrelationHeaders.JOURNEY_ID);
            return ResponseEntity.status(ex.getHttpStatus()).body(
                    ApiErrorResponse.of(
                            ex.getHttpStatus(),
                            ex.getErrorCode(),
                            ex.getMessage(),
                            runId,
                            journeyId,
                            TelemetryHelper.currentTraceId()
                    )
            );
        } finally {
            TelemetryHelper.clearMdc();
        }
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(
            MethodArgumentNotValidException ex,
            HttpServletRequest request
    ) {
        try {
            String runId = request.getHeader(CorrelationHeaders.RUN_ID);
            String journeyId = request.getHeader(CorrelationHeaders.JOURNEY_ID);
            TelemetryHelper.recordError(JourneyState.INTERNAL_FAILURE, "VALIDATION_ERROR", ex);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    ApiErrorResponse.of(
                            HttpStatus.BAD_REQUEST.value(),
                            "VALIDATION_ERROR",
                            "Invalid checkout request payload",
                            runId,
                            journeyId,
                            TelemetryHelper.currentTraceId()
                    )
            );
        } finally {
            TelemetryHelper.clearMdc();
        }
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(
            Exception ex,
            HttpServletRequest request
    ) {
        try {
            String runId = request.getHeader(CorrelationHeaders.RUN_ID);
            String journeyId = request.getHeader(CorrelationHeaders.JOURNEY_ID);
            TelemetryHelper.recordError(JourneyState.INTERNAL_FAILURE, "INTERNAL_FAILURE", ex);
            log.error("Unexpected checkout service failure", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    ApiErrorResponse.of(
                            HttpStatus.INTERNAL_SERVER_ERROR.value(),
                            "INTERNAL_FAILURE",
                            "Unexpected internal error in checkout service",
                            runId,
                            journeyId,
                            TelemetryHelper.currentTraceId()
                    )
            );
        } finally {
            TelemetryHelper.clearMdc();
        }
    }
}
