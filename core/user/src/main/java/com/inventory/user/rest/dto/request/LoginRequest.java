package com.inventory.user.rest.dto.request;

import lombok.Data;

@Data
public class LoginRequest {
  // For email/password authentication
  private String email;
  private String password;

  // For OAuth authentication (Google/Facebook)
  private String idToken;
  private String loginType; // "google" or "facebook"

  private String deviceId; // Optional: if not provided, will be generated

  /**
   * Where the sign-in came from: {@link AuthSource#WEB} (default when absent) or {@link
   * AuthSource#WORKER_APP}. Tags {@code inventory_user_auth_total{source}} and is handed to the
   * login e-mail template (worker-app R1.8). {@code AuthValidator} normalises it.
   */
  private String source;
}
