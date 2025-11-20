package com.inventory.user.rest.dto.user;

import lombok.Data;

@Data
public class AddUserRequest {
    private String name;
    private String email;
    private String role;
}

