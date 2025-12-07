package com.inventory.user.rest.dto.user;

import com.inventory.user.domain.model.UserRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserInviteDto {
  private String inviteId;
  private String email;
  private String name;
  private UserRole role;
  private String shopId;
  private Instant expiresAt;
  private boolean accepted;
}
