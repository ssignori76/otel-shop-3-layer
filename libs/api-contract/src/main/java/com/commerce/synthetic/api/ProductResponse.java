package com.commerce.synthetic.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ProductResponse(
        @JsonProperty("sku") String sku,
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("price_cents") int priceCents,
        @JsonProperty("currency") String currency,
        @JsonProperty("category") String category,
        @JsonProperty("badge") String badge
) {
}
