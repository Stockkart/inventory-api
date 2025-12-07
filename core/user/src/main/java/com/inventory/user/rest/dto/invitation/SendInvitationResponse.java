package com.inventory.user.rest.dto.invitation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SendInvitationResponse {
  private String invitationId;
  private String shopId;
  private String inviteeEmail;
  private String role;
  private String status;
  private Instant createdAt;
  private Instant expiresAt;
  private String message;
}

