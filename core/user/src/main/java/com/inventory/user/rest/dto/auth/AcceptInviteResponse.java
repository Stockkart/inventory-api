package com.inventory.user.rest.dto.auth;

import com.inventory.user.domain.model.UserRole;
import lombok.Data;

@Data
public class AcceptInviteResponse {
  String userId;
  UserRole role;
  String shopId;
  boolean active;
}

