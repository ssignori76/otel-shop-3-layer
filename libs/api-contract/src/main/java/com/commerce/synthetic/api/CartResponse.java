package com.commerce.synthetic.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CartResponse(
        @JsonProperty("cart_id") String cartId,
        @JsonProperty("journey_id") String journeyId,
        @JsonProperty("status") String status,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt
) {
}
