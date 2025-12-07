package com.inventory.user.rest.dto.invitation;

import lombok.Data;

@Data
public class SendInvitationRequest {
  private String inviteeEmail;
  private String role;
}

