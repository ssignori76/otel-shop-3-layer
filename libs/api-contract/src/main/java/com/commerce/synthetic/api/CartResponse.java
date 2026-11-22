package com.commerce.synthetic.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CartResponse(
        @JsonProperty("cart_id") String cartId,
        @JsonProperty("journey_id") String journeyId,
        @JsonProperty("status") String status,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt,
        @JsonProperty("user_id") String userId,
        @JsonProperty("items") List<CartItemResponse> items,
        @JsonProperty("total_cents") Integer totalCents
) {
    /**
     * Backward-compatible constructor for v1 synthetic journeys.
     */
    public CartResponse(
            String cartId,
            String journeyId,
            String status,
            String createdAt,
            String updatedAt
    ) {
        this(cartId, journeyId, status, createdAt, updatedAt, null, null, null);
    }
}
