package com.inventory.user.rest.dto.user;

import com.inventory.user.domain.model.UserRole;
import lombok.Data;

@Data
public class UserDto {
  String userId;
  String name;
  UserRole role;
  boolean active;
}

