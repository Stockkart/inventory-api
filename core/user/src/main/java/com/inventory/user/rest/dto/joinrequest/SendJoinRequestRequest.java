package com.inventory.user.rest.dto.joinrequest;

import com.inventory.user.domain.model.UserRole;
import lombok.Data;

@Data
public class SendJoinRequestRequest {
  private String ownerEmail;
  private UserRole role; // Role requested by the user
  private String message; // Optional message from user
}

