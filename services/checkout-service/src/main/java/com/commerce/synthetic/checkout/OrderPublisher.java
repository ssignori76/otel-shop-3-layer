package com.commerce.synthetic.checkout;

import com.commerce.synthetic.messaging.OrderCreatedMessage;
import com.commerce.synthetic.messaging.RabbitTopologyConstants;
import com.commerce.synthetic.scenario.CorrelationHeaders;
import com.commerce.synthetic.scenario.ScenarioContext;
import com.commerce.synthetic.scenario.ScenarioName;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Component
public class OrderPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final long publishTimeoutMs;

    public OrderPublisher(
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            @Value("${commerce.scenario.broker-publish-timeout-ms:1500}") long publishTimeoutMs
    ) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.publishTimeoutMs = publishTimeoutMs;
    }

    public void publishOrderWithConfirm(OrderCreatedMessage payload, ScenarioContext ctx) {
        if (ctx.isScenarioActive(ScenarioName.BROKER_UNAVAILABLE)) {
            throw new AmqpException("Synthetic BROKER_UNAVAILABLE scenario: publisher confirm negative or broker unreachable");
        }

        try {
            byte[] body = objectMapper.writeValueAsBytes(payload);
            Message amqpMessage = MessageBuilder.withBody(body)
                    .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                    .setContentEncoding(StandardCharsets.UTF_8.name())
                    .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                    .setMessageId(payload.messageId())
                    .setCorrelationId(payload.journeyId())
                    .setHeader(CorrelationHeaders.RUN_ID, ctx.runId())
                    .setHeader(CorrelationHeaders.JOURNEY_ID, ctx.journeyId())
                    .setHeader(CorrelationHeaders.SIMULATION_SCENARIO, ctx.scenarioName().name())
                    .setHeader(CorrelationHeaders.SIMULATION_ENABLED, Boolean.toString(ctx.scenarioEnabled()))
                    .build();

            CorrelationData correlationData = new CorrelationData(payload.messageId());
            rabbitTemplate.send(
                    RabbitTopologyConstants.ORDERS_EXCHANGE,
                    RabbitTopologyConstants.ORDER_CREATED_ROUTING_KEY,
                    amqpMessage,
                    correlationData
            );

            CorrelationData.Confirm confirm = correlationData.getFuture()
                    .get(publishTimeoutMs, TimeUnit.MILLISECONDS);
            if (confirm == null || !confirm.isAck()) {
                String reason = confirm != null ? confirm.getReason() : "null confirm";
                throw new AmqpException("RabbitMQ publisher confirm negative (NACK): " + reason);
            }
        } catch (AmqpException e) {
            throw e;
        } catch (Exception e) {
            throw new AmqpException("Failed to publish order message or receive publisher confirm", e);
        }
    }
}
