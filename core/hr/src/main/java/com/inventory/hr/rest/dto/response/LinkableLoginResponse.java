package com.inventory.hr.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A shop login that can be linked to an employee. {@code email} and {@code phone} come from the
 * login's account so the add-employee form can be prefilled; they are set only for a caller with
 * VIEW_SALARIES (the permission linking needs) and are null for everyone else.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LinkableLoginResponse {
  private String userId;
  private String name;
  private String role;
  /** Employee already linked to this login; null = free. */
  private String linkedEmployeeId;
  /** Login's email; null without VIEW_SALARIES or when the account has none. */
  private String email;
  /** Login's phone; null without VIEW_SALARIES or when the account has none. */
  private String phone;
}
