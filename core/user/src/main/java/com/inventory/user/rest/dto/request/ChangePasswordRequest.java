package com.inventory.user.rest.dto.request;

import lombok.Data;

@Data
public class ChangePasswordRequest {
  /** Optional; when sent it must be the signed-in account's email. */
  private String email;
  /** The account's current password (required). */
  private String currentPassword;
  /** The new password. */
  private String password;
}

