package com.inventory.user.rest.dto.auth;

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
}

