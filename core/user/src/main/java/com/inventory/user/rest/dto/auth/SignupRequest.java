package com.inventory.user.rest.dto.auth;

import lombok.Data;

@Data
public class SignupRequest {
  private String name;
  private String email;
  private String password;
  private String shopId;
  private String role;
  private String deviceId; // Optional: if not provided, will be generated
}

