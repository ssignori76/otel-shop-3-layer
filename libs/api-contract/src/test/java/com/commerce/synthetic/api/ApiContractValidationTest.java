package com.commerce.synthetic.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApiContractValidationTest {

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
    void validatesJourneyAndCartAndCheckoutRequests() throws Exception {
        String runId = UUID.randomUUID().toString();
        String journeyId = UUID.randomUUID().toString();
        String cartId = UUID.randomUUID().toString();

        JourneyStartRequest validStart = new JourneyStartRequest(runId, journeyId, "SKU-SYNTHETIC-01", 1);
        assertThat(validator.validate(validStart)).isEmpty();

        JourneyStartRequest invalidStart = new JourneyStartRequest("not-a-uuid", "", "", 0);
        assertThat(validator.validate(invalidStart)).hasSize(4);

        CreateCartRequest validCart = new CreateCartRequest(runId, journeyId, "SKU-SYNTHETIC-01", 1);
        assertThat(validator.validate(validCart)).isEmpty();

        CheckoutRequest validCheckout = new CheckoutRequest(runId, journeyId, cartId);
        assertThat(validator.validate(validCheckout)).isEmpty();

        PaymentAuthorizeRequest validPayment = new PaymentAuthorizeRequest(runId, journeyId, cartId);
        assertThat(validator.validate(validPayment)).isEmpty();

        ApiErrorResponse error = ApiErrorResponse.of(504, "DB_TIMEOUT", "Database operation timed out", runId, journeyId, "0123456789abcdef0123456789abcdef");
        String json = objectMapper.writeValueAsString(error);
        assertThat(json).contains("\"error_code\":\"DB_TIMEOUT\"");
        assertThat(json).contains("\"status\":504");
        assertThat(json).contains("\"run_id\":\"" + runId + "\"");
    }
}
