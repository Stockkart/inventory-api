package com.inventory.user.rest.dto.response;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** An admin as other admins see it: never includes the password hash or lockout state. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminUserResponse {

  private String id;
  private String email;
  private String name;
  private boolean active;
  private boolean mustChangePassword;
  private String createdByAdminId;
  private Instant createdAt;
  private Instant lastLoginAt;
}
