package com.inventory.user.rest.dto.response;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminLoginResponse {

  private String token;
  private Instant expiresAt;
  private AdminUserResponse admin;
}
