package com.commerce.synthetic.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MessagingContractTest {

    private static Validator validator;
    private static ObjectMapper objectMapper;

    @BeforeAll
    static void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
        objectMapper = new ObjectMapper();
    }

    @Test
    void serializesAndValidatesOrderCreatedMessage() throws Exception {
        OrderCreatedMessage msg = new OrderCreatedMessage(
                RabbitTopologyConstants.SCHEMA_VERSION_V1,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                Instant.now().toString(),
                "NORMAL",
                false
        );

        assertThat(validator.validate(msg)).isEmpty();
        String json = objectMapper.writeValueAsString(msg);
        OrderCreatedMessage roundTrip = objectMapper.readValue(json, OrderCreatedMessage.class);
        assertThat(roundTrip).isEqualTo(msg);
    }
}
