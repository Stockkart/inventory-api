package com.inventory.user.domain.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
public class UserToken {
  private String accessToken;
  private String refreshToken;
  private String deviceId;
  private Instant createdAt;
  private Instant expiresAt;
  /**
   * Which client opened the session: {@code "web"} or {@code "worker-app"}, as the sign-in or
   * sign-up request named it. Read back on logout so the sign-out is counted under the same
   * {@code source} as the sign-in (worker-app R1.8). {@code null} on tokens minted before the
   * field existed and means the web.
   */
  private String source;

  public UserToken(String accessToken, String refreshToken, String deviceId, Instant createdAt,
      Instant expiresAt) {
    this.accessToken = accessToken;
    this.refreshToken = refreshToken;
    this.deviceId = deviceId;
    this.createdAt = createdAt;
    this.expiresAt = expiresAt;
  }
}
