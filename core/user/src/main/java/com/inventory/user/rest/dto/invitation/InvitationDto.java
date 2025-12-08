package com.inventory.user.rest.dto.invitation;

import com.inventory.user.domain.model.UserRole;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class InvitationDto {
  private String invitationId;
  private String shopId;
  private String shopName;
  private String inviterUserId;
  private String inviterName;
  private String inviteeUserId;
  private String inviteeEmail;
  private String inviteeName;
  private UserRole role;
  private String status;
  private Instant createdAt;
  private Instant expiresAt;
  private Instant acceptedAt;
  private Instant rejectedAt;
}

