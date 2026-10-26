package com.inventory.hr.rest.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Body of actions that need a reason: advance cancel, payroll reopen. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReasonRequest {
  private String reason;
}
