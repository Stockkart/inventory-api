package com.inventory.plan.rest.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReferralApprovalRequest {

  /** Required when the attribution has no referrer yet (name-only or unknown code). */
  private String referrerShopId;
  /** Required; kept in the audit log. */
  private String reason;
}
