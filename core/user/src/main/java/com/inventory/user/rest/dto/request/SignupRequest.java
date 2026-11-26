package com.inventory.user.rest.dto.request;

import com.inventory.user.domain.model.UserRole;
import lombok.Data;

@Data
public class SignupRequest {
  // For email/password signup
  private String name;
  private String email;
  private String password;
  /** 10-digit Indian mobile (FE may send +91…; AuthValidator normalizes). */
  private String phone;

  // For OAuth signup (Google/Facebook)
  private String idToken;
  private String signupType; // "google" or "facebook"

  private String shopId;
  private UserRole role;
  private String deviceId; // Optional: if not provided, will be generated

  /**
   * Where the sign-up came from: {@link AuthSource#WEB} (default when absent) or {@link
   * AuthSource#WORKER_APP}. Tags {@code inventory_user_auth_total{source}} and is handed to the
   * welcome e-mail template (worker-app R1.8). {@code AuthValidator} normalises it.
   */
  private String source;
}
