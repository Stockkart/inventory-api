package com.inventory.user.rest.dto.request;

import com.inventory.user.domain.model.UserRole;
import lombok.Data;

@Data
public class UpdateUserRequest {
  private String name;
  private UserRole role;
  private Boolean active;
}
