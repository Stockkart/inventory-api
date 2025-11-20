package com.inventory.user.rest.dto.user;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class DeactivateUserResponse {
    String userId;
    boolean active;
}

