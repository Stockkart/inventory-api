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
public class AcceptInvitationResponse {
  private String invitationId;
  private String shopId;
  private String shopName;
  private String userId;
  private String role;
  private Instant acceptedAt;
  private String message;
}

