package com.inventory.user.rest.dto.auth;

import lombok.Data;

@Data
public class LogoutRequest {
  private String userId; // Required: user to logout
  private String deviceId; // Optional: if provided, removes token for this device
  private String accessToken; // Optional: if provided, removes this specific token
  // Note: userId is required. At least one of deviceId or accessToken should be provided
}

