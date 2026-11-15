package com.commerce.synthetic.messaging;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Order message contract per Section 13.2 of the specification.
 */
public record OrderCreatedMessage(
        @NotBlank
        @JsonProperty("schema_version")
        String schemaVersion,

        @NotBlank
        @Pattern(regexp = "^[0-9a-fA-F-]{36}$")
        @JsonProperty("message_id")
        String messageId,

        @NotBlank
        @Pattern(regexp = "^[0-9a-fA-F-]{36}$")
        @JsonProperty("run_id")
        String runId,

        @NotBlank
        @Pattern(regexp = "^[0-9a-fA-F-]{36}$")
        @JsonProperty("journey_id")
        String journeyId,

        @NotBlank
        @Pattern(regexp = "^[0-9a-fA-F-]{36}$")
        @JsonProperty("order_id")
        String orderId,

        @NotBlank
        @Pattern(regexp = "^[0-9a-fA-F-]{36}$")
        @JsonProperty("cart_id")
        String cartId,

        @NotBlank
        @JsonProperty("payment_authorization_id")
        String paymentAuthorizationId,

        @NotBlank
        @JsonProperty("created_at")
        String createdAt,

        @NotBlank
        @JsonProperty("scenario_name")
        String scenarioName,

        @JsonProperty("scenario_enabled")
        boolean scenarioEnabled
) {
}
