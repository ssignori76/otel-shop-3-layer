package com.commerce.synthetic.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public record FulfillmentStatusResponse(
        @JsonProperty("fulfillment_id") String fulfillmentId,
        @JsonProperty("order_id") String orderId,
        @JsonProperty("journey_id") String journeyId,
        @JsonProperty("status") String status,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt
) {
}
