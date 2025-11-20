package com.inventory.user.rest.dto.auth;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class LoginResponse {
    String accessToken;
    String refreshToken;
    UserSummary user;

    @Value
    @Builder
    public static class UserSummary {
        String userId;
        String role;
        String shopId;
    }
}

