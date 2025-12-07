package com.inventory.user.rest.dto.auth;

import com.inventory.user.domain.model.UserRole;
import lombok.Data;

@Data
public class SignupRequest {
  // For email/password signup
  private String name;
  private String email;
  private String password;
  
  // For Google signup
  private String idToken;
  
  private String shopId;
  private UserRole role;
  private String deviceId; // Optional: if not provided, will be generated
}

