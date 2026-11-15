package com.commerce.synthetic.fulfillment;

import com.commerce.synthetic.messaging.OrderCreatedMessage;
import com.commerce.synthetic.messaging.RabbitTopologyConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:fulfillment_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "management.health.rabbit.enabled=false"
})
class OrderFulfillmentListenerTest {

    @Autowired
    private FulfillmentRepository fulfillmentRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private OrderFulfillmentListener listener;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS fulfillment (
                    fulfillment_id VARCHAR(36) NOT NULL PRIMARY KEY,
                    order_id VARCHAR(36) NOT NULL UNIQUE,
                    journey_id VARCHAR(36) NOT NULL UNIQUE,
                    status VARCHAR(64) NOT NULL,
                    created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL
                )
                """);
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        listener = new OrderFulfillmentListener(fulfillmentRepository, objectMapper, validator, "NORMAL", false, 10L);
    }

    @Test
    void persistsFulfillmentAndAcksOnlyAfterCommitAndIsIdempotent() throws Exception {
        String orderId = UUID.randomUUID().toString();
        String journeyId = UUID.randomUUID().toString();
        OrderCreatedMessage payload = new OrderCreatedMessage(
                RabbitTopologyConstants.SCHEMA_VERSION_V1,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                journeyId,
                orderId,
                UUID.randomUUID().toString(),
                "auth-1",
                Instant.now().toString(),
                "NORMAL",
                false
        );

        MessageProperties props = new MessageProperties();
        props.setDeliveryTag(42L);
        Message msg = new Message(objectMapper.writeValueAsBytes(payload), props);
        Channel channel = mock(Channel.class);

        listener.onOrderCreated(msg, channel);
        verify(channel).basicAck(42L, false);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fulfillment WHERE order_id = ? AND status = 'FULFILLED'",
                Integer.class,
                orderId
        );
        assertThat(count).isEqualTo(1);

        // Redelivery of the same order_id must remain idempotent
        MessageProperties props2 = new MessageProperties();
        props2.setDeliveryTag(43L);
        Message redelivered = new Message(objectMapper.writeValueAsBytes(payload), props2);
        listener.onOrderCreated(redelivered, channel);
        verify(channel).basicAck(43L, false);

        Integer countAfterRedelivery = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fulfillment WHERE order_id = ?",
                Integer.class,
                orderId
        );
        assertThat(countAfterRedelivery).isEqualTo(1);
    }

    @Test
    void fulfillmentErrorScenarioNacksToDlqWithoutPersisting() throws Exception {
        String orderId = UUID.randomUUID().toString();
        OrderCreatedMessage payload = new OrderCreatedMessage(
                RabbitTopologyConstants.SCHEMA_VERSION_V1,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                orderId,
                UUID.randomUUID().toString(),
                "auth-2",
                Instant.now().toString(),
                "FULFILLMENT_ERROR",
                true
        );

        MessageProperties props = new MessageProperties();
        props.setDeliveryTag(55L);
        Message msg = new Message(objectMapper.writeValueAsBytes(payload), props);
        Channel channel = mock(Channel.class);

        listener.onOrderCreated(msg, channel);
        verify(channel).basicNack(55L, false, false);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fulfillment WHERE order_id = ?",
                Integer.class,
                orderId
        );
        assertThat(count).isZero();
    }

    @Test
    void poisonMessageIsNackedToDlqWithoutLoop() throws Exception {
        MessageProperties props = new MessageProperties();
        props.setDeliveryTag(77L);
        Message poison = new Message("{not-valid-json".getBytes(StandardCharsets.UTF_8), props);
        Channel channel = mock(Channel.class);

        listener.onOrderCreated(poison, channel);
        verify(channel).basicNack(77L, false, false);
    }
}
