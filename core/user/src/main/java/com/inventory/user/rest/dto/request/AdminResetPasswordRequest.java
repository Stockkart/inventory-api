package com.inventory.user.rest.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Sets a new password from the one-time link an admin password reset emails to the account. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminResetPasswordRequest {

  private String token;
  private String newPassword;
}
