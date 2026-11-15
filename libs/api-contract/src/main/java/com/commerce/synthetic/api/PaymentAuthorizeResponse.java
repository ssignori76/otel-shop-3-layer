package com.commerce.synthetic.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PaymentAuthorizeResponse(
        @JsonProperty("payment_authorization_id") String paymentAuthorizationId,
        @JsonProperty("journey_id") String journeyId,
        @JsonProperty("cart_id") String cartId,
        @JsonProperty("status") String status,
        @JsonProperty("authorized_at") String authorizedAt
) {
}
