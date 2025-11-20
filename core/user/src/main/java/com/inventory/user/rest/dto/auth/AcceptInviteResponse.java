package com.inventory.user.rest.dto.auth;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class AcceptInviteResponse {
    String userId;
    String role;
    String shopId;
    boolean active;
}

