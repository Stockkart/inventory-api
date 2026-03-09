package com.inventory.user.rest.dto.response;

import com.inventory.user.domain.model.UserRole;
import lombok.Data;

@Data
public class AcceptInviteResponse {
  String userId;
  UserRole role;
  String shopId;
  boolean active;
}
