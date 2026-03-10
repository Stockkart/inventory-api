package com.inventory.user.rest.dto.response;

import lombok.Data;

@Data
public class LogoutResponse {
  private String message;
  private boolean success;
  private String deviceId; // The device that was logged out
}
