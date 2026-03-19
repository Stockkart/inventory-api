package com.inventory.user.rest.dto.request;

import lombok.Data;

@Data
public class ResetPasswordRequest {
  private String token;
  private String newPassword;
}
