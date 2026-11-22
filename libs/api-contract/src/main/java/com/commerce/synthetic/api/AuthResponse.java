package com.commerce.synthetic.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AuthResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in_seconds") long expiresInSeconds,
        @JsonProperty("user") UserProfileResponse user
) {
}
