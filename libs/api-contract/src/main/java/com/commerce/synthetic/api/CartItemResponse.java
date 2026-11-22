package com.commerce.synthetic.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CartItemResponse(
        @JsonProperty("sku") String sku,
        @JsonProperty("name") String name,
        @JsonProperty("quantity") int quantity,
        @JsonProperty("unit_price_cents") int unitPriceCents,
        @JsonProperty("line_total_cents") int lineTotalCents
) {
}
