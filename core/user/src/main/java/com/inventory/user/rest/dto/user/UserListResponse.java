package com.inventory.user.rest.dto.user;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class UserListResponse {
    @Singular("user")
    List<UserDto> data;
}

