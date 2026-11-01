package com.inventory.user.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Returned once when an admin is added or their password is reset; the password is not stored. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminPasswordIssuedResponse {

  private AdminUserResponse admin;
  private String temporaryPassword;
}
