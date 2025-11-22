package com.inventory.user.rest.dto.user;

import lombok.Data;

@Data
public class UserDto {
  String userId;
  String name;
  String role;
  boolean active;
}

