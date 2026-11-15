package com.commerce.synthetic.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CheckoutResponse(
        @JsonProperty("order_id") String orderId,
        @JsonProperty("journey_id") String journeyId,
        @JsonProperty("cart_id") String cartId,
        @JsonProperty("payment_authorization_id") String paymentAuthorizationId,
        @JsonProperty("status") String status,
        @JsonProperty("trace_id") String traceId
) {
}
