package com.inventory.user.rest.dto.request;

import com.inventory.user.domain.model.UserRole;
import lombok.Data;

@Data
public class SendInvitationRequest {
  private String inviteeEmail;
  private UserRole role;
}
