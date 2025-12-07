package com.inventory.user.rest.dto.user;

import com.inventory.user.domain.model.UserRole;
import lombok.Data;

@Data
public class AddUserRequest {
  private String name;
  private String email;
  private UserRole role;
}

