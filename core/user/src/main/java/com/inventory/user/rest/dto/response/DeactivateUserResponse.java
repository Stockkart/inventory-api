package com.inventory.user.rest.dto.response;

import lombok.Data;

@Data
public class DeactivateUserResponse {
  String userId;
  boolean active;
}
