package com.inventory.user.rest.dto.request;

import lombok.Data;

@Data
public class ChangePasswordRequest {
  private String email;
  private String password;
}
