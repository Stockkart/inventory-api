package com.inventory.user.rest.dto.user;

import lombok.Data;
import lombok.Singular;

import java.util.List;

@Data
public class UserListResponse {
    @Singular("user")
    List<UserDto> data;
}

