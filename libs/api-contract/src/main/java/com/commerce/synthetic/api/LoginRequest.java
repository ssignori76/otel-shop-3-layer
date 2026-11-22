package com.commerce.synthetic.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank(message = "email is required")
        @Email(message = "email must be valid")
        @JsonProperty("email")
        String email,

        @NotBlank(message = "password is required")
        @JsonProperty("password")
        String password
) {
}
