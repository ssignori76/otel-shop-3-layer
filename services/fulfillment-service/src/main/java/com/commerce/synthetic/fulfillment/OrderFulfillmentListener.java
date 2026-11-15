package com.commerce.synthetic.fulfillment;

import com.commerce.synthetic.messaging.OrderCreatedMessage;
import com.commerce.synthetic.messaging.RabbitTopologyConstants;
import com.commerce.synthetic.scenario.JourneyState;
import com.commerce.synthetic.scenario.ScenarioContext;
import com.commerce.synthetic.scenario.ScenarioException;
import com.commerce.synthetic.scenario.ScenarioName;
import com.commerce.synthetic.scenario.TelemetryHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

@Component
public class OrderFulfillmentListener {

    private static final Logger log = LoggerFactory.getLogger(OrderFulfillmentListener.class);

    private final FulfillmentRepository fulfillmentRepository;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final String defaultScenario;
    private final boolean defaultScenarioEnabled;
    private final long fulfillmentErrorAfterMs;

    public OrderFulfillmentListener(
            FulfillmentRepository fulfillmentRepository,
            ObjectMapper objectMapper,
            Validator validator,
            @Value("${commerce.scenario.name:NORMAL}") String defaultScenario,
            @Value("${commerce.scenario.enabled:false}") boolean defaultScenarioEnabled,
            @Value("${commerce.scenario.fulfillment-error-after-ms:100}") long fulfillmentErrorAfterMs
    ) {
        this.fulfillmentRepository = fulfillmentRepository;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.defaultScenario = defaultScenario;
        this.defaultScenarioEnabled = defaultScenarioEnabled;
        this.fulfillmentErrorAfterMs = fulfillmentErrorAfterMs;
    }

    @RabbitListener(queues = RabbitTopologyConstants.FULFILLMENT_QUEUE, ackMode = "MANUAL")
    public void onOrderCreated(Message rawMessage, Channel channel) throws IOException {
        long startNanos = System.nanoTime();
        long deliveryTag = rawMessage.getMessageProperties().getDeliveryTag();

        OrderCreatedMessage orderMessage;
        try {
            String body = new String(rawMessage.getBody(), StandardCharsets.UTF_8);
            orderMessage = objectMapper.readValue(body, OrderCreatedMessage.class);
            Set<ConstraintViolation<OrderCreatedMessage>> violations = validator.validate(orderMessage);
            if (!violations.isEmpty()) {
                throw new IllegalArgumentException("Invalid OrderCreatedMessage contract: " + violations);
            }
        } catch (Exception parseEx) {
            TelemetryHelper.recordError(JourneyState.FULFILLMENT_FAILED, "POISON_MESSAGE", parseEx);
            TelemetryHelper.incrementCounter(
                    "commerce.fulfillments.failed",
                    "{fulfillment}",
                    "Total failed fulfillments in fulfillment-service",
                    Map.of(
                            "commerce.scenario", "UNKNOWN",
                            "commerce.journey.state", JourneyState.FULFILLMENT_FAILED.name(),
                            "error.code", "POISON_MESSAGE"
                    )
            );
            log.error("Poison order message rejected to DLQ: delivery_tag={}", deliveryTag, parseEx);
            channel.basicNack(deliveryTag, false, false);
            TelemetryHelper.clearMdc();
            return;
        }

        ScenarioContext ctx = ScenarioContext.resolve(
                orderMessage.runId(),
                orderMessage.journeyId(),
                orderMessage.scenarioName(),
                Boolean.toString(orderMessage.scenarioEnabled()),
                defaultScenario,
                defaultScenarioEnabled
        );

        try {
            TelemetryHelper.bindContext(ctx, JourneyState.ORDER_PUBLISHED);
            TelemetryHelper.bindEntityIds(orderMessage.cartId(), orderMessage.orderId(), null);
            TelemetryHelper.incrementCounter(
                    "commerce.fulfillments.received",
                    "{fulfillment}",
                    "Total order messages received by fulfillment-service",
                    Map.of("commerce.scenario", ctx.effectiveScenario().name())
            );

            if (ctx.isScenarioActive(ScenarioName.FULFILLMENT_ERROR)) {
                if (fulfillmentErrorAfterMs > 0) {
                    Thread.sleep(fulfillmentErrorAfterMs);
                }
                ScenarioException scenarioEx = new ScenarioException(
                        ScenarioName.FULFILLMENT_ERROR,
                        JourneyState.FULFILLMENT_FAILED,
                        500,
                        "FULFILLMENT_ERROR",
                        "Synthetic fulfillment processing failure injected for order " + orderMessage.orderId()
                );
                double durationMs = (System.nanoTime() - startNanos) / 1_000_000.0;
                TelemetryHelper.recordError(JourneyState.FULFILLMENT_FAILED, "FULFILLMENT_ERROR", scenarioEx);
                TelemetryHelper.incrementCounter(
                        "commerce.fulfillments.failed",
                        "{fulfillment}",
                        "Total failed fulfillments in fulfillment-service",
                        Map.of(
                                "commerce.scenario", ctx.effectiveScenario().name(),
                                "commerce.journey.state", JourneyState.FULFILLMENT_FAILED.name(),
                                "error.code", "FULFILLMENT_ERROR"
                        )
                );
                TelemetryHelper.recordHistogram(
                        "commerce.fulfillment.duration",
                        "ms",
                        "Order fulfillment processing duration in ms",
                        durationMs,
                        Map.of(
                                "commerce.scenario", ctx.effectiveScenario().name(),
                                "commerce.journey.state", JourneyState.FULFILLMENT_FAILED.name()
                        )
                );
                log.error("Fulfillment failed due to scenario FULFILLMENT_ERROR: order_id={} state={}",
                        orderMessage.orderId(), JourneyState.FULFILLMENT_FAILED);
                // Reject without requeue so it lands deterministically in DLQ without infinite loop (Section 13.3)
                channel.basicNack(deliveryTag, false, false);
                return;
            }

            // Persist fulfillment in a committed MySQL transaction BEFORE sending RabbitMQ ACK (Section 7.1 Step 16)
            String fulfillmentId = fulfillmentRepository.persistFulfillmentCommitted(
                    orderMessage.orderId(),
                    orderMessage.journeyId()
            );

            TelemetryHelper.bindEntityIds(orderMessage.cartId(), orderMessage.orderId(), fulfillmentId);
            TelemetryHelper.updateState(JourneyState.FULFILLED);

            // Manual ACK strictly after MySQL transaction commit
            channel.basicAck(deliveryTag, false);

            double durationMs = (System.nanoTime() - startNanos) / 1_000_000.0;
            TelemetryHelper.incrementCounter(
                    "commerce.fulfillments.completed",
                    "{fulfillment}",
                    "Total completed fulfillments in fulfillment-service",
                    Map.of(
                            "commerce.scenario", ctx.effectiveScenario().name(),
                            "commerce.journey.state", JourneyState.FULFILLED.name()
                    )
            );
            TelemetryHelper.recordHistogram(
                    "commerce.fulfillment.duration",
                    "ms",
                    "Order fulfillment processing duration in ms",
                    durationMs,
                    Map.of(
                            "commerce.scenario", ctx.effectiveScenario().name(),
                            "commerce.journey.state", JourneyState.FULFILLED.name()
                    )
            );

            log.info("Order fulfillment persisted and acknowledged: fulfillment_id={} order_id={} state={}",
                    fulfillmentId, orderMessage.orderId(), JourneyState.FULFILLED);
        } catch (Exception ex) {
            TelemetryHelper.recordError(JourneyState.INTERNAL_FAILURE, "INTERNAL_FAILURE", ex);
            TelemetryHelper.incrementCounter(
                    "commerce.fulfillments.failed",
                    "{fulfillment}",
                    "Total failed fulfillments in fulfillment-service",
                    Map.of(
                            "commerce.scenario", ctx.effectiveScenario().name(),
                            "commerce.journey.state", JourneyState.INTERNAL_FAILURE.name(),
                            "error.code", "INTERNAL_FAILURE"
                    )
            );
            log.error("Unexpected failure processing fulfillment for order_id={}", orderMessage.orderId(), ex);
            channel.basicNack(deliveryTag, false, false);
        } finally {
            TelemetryHelper.clearMdc();
        }
    }
}
