package com.commerce.synthetic.auth;

import com.commerce.synthetic.api.UserProfileResponse;

public record UserRecord(
        String userId,
        String email,
        String fullName,
        String passwordHash,
        String createdAt
) {
    public UserProfileResponse toProfile() {
        return new UserProfileResponse(userId, email, fullName, createdAt);
    }
}
