package com.inventory.user.rest.dto.auth;

import lombok.Data;

@Data
public class AcceptInviteResponse {
    String userId;
    String role;
    String shopId;
    boolean active;
}

