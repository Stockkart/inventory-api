package com.inventory.user.rest.dto.user;

import lombok.Data;

@Data
public class DeactivateUserResponse {
  String userId;
  boolean active;
}

