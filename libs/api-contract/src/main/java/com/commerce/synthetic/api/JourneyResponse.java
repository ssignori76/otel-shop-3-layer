package com.commerce.synthetic.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public record JourneyResponse(
        @JsonProperty("run_id") String runId,
        @JsonProperty("journey_id") String journeyId,
        @JsonProperty("cart_id") String cartId,
        @JsonProperty("order_id") String orderId,
        @JsonProperty("payment_authorization_id") String paymentAuthorizationId,
        @JsonProperty("state") String state,
        @JsonProperty("trace_id") String traceId
) {
}
