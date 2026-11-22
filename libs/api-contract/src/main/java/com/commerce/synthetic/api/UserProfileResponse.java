package com.commerce.synthetic.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UserProfileResponse(
        @JsonProperty("user_id") String userId,
        @JsonProperty("email") String email,
        @JsonProperty("full_name") String fullName,
        @JsonProperty("created_at") String createdAt
) {
}
