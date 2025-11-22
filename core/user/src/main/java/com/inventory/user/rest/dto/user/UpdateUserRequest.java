package com.inventory.user.rest.dto.user;

import lombok.Data;

@Data
public class UpdateUserRequest {
  private String name;
  private String role;
  private Boolean active;
}

