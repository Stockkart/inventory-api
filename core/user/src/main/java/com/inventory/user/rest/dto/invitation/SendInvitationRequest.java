package com.inventory.user.rest.dto.invitation;

import com.inventory.user.domain.model.UserRole;
import lombok.Data;

@Data
public class SendInvitationRequest {
  private String inviteeEmail;
  private UserRole role;
}

