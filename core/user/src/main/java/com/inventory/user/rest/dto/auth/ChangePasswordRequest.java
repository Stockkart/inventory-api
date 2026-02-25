package com.inventory.user.rest.dto.auth;

import lombok.Data;

@Data
public class ChangePasswordRequest {
  private String email;
  private String password;
}
