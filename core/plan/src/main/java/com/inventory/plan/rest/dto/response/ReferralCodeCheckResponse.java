package com.inventory.plan.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Only what the signup screen renders. Never the shop id, owner, contacts, address or plan (§11). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReferralCodeCheckResponse {
  private boolean valid;
  private String displayName;
}
