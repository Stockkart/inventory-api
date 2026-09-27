package com.inventory.plan.rest.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CampaignActiveRequest {

  private Boolean active;
  /** Why the campaign was switched on or off; kept in the audit log. */
  private String reason;
}
