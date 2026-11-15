package com.commerce.synthetic.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateCartRequest(
        @NotBlank
        @Pattern(regexp = "^[0-9a-fA-F-]{36}$", message = "run_id must be a canonical UUID")
        @JsonProperty("run_id")
        String runId,

        @NotBlank
        @Pattern(regexp = "^[0-9a-fA-F-]{36}$", message = "journey_id must be a canonical UUID")
        @JsonProperty("journey_id")
        String journeyId,

        @NotBlank
        @JsonProperty("sku")
        String sku,

        @Min(1)
        @JsonProperty("quantity")
        int quantity
) {
}
