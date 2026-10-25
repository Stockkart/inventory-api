package com.inventory.hr.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A shop login that can be linked to an employee; no email or phone. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LinkableLoginResponse {
  private String userId;
  private String name;
  private String role;
  /** Employee already linked to this login; null = free. */
  private String linkedEmployeeId;
}
