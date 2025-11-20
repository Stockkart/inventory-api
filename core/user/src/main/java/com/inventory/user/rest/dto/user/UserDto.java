package com.inventory.user.rest.dto.user;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class UserDto {
    String userId;
    String name;
    String role;
    boolean active;
}

