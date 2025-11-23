package com.inventory.user.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserToken {
  private String accessToken;
  private String refreshToken;
  private String deviceId;
  private Instant createdAt;
  private Instant expiresAt;
}

