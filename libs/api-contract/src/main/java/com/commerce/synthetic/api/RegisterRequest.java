package com.commerce.synthetic.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "email is required")
        @Email(message = "email must be valid")
        @Size(max = 255)
        @JsonProperty("email")
        String email,

        @NotBlank(message = "full_name is required")
        @Size(min = 2, max = 120)
        @JsonProperty("full_name")
        String fullName,

        @NotBlank(message = "password is required")
        @Size(min = 8, max = 128, message = "password must be between 8 and 128 characters")
        @JsonProperty("password")
        String password
) {
}
