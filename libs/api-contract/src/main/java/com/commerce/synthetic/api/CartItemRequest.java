package com.commerce.synthetic.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record CartItemRequest(
        @NotBlank(message = "sku is required")
        @JsonProperty("sku")
        String sku,

        @Min(value = 1, message = "quantity must be at least 1")
        @Max(value = 99, message = "quantity must be at most 99")
        @JsonProperty("quantity")
        int quantity
) {
}
