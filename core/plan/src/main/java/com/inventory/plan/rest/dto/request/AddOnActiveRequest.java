package com.inventory.plan.rest.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AddOnActiveRequest {

  private Boolean active;
  /** Kept in the audit log. */
  private String reason;
}
