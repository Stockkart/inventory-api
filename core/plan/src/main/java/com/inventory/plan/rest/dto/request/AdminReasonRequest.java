package com.inventory.plan.rest.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Body for admin actions that only need a reason, which is required and audited. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminReasonRequest {

  private String reason;
}
