package com.inventory.plan.rest.dto.response;

import com.inventory.common.entitlement.PlanFeature;
import com.inventory.plan.domain.model.EntitlementEnforcementMode;
import com.inventory.plan.domain.model.EntitlementSource;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * What the current shop may use. Null limits mean unlimited.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShopEntitlementsResponse {

  private String planId;
  private String planCode;
  private EntitlementSource source;
  private List<PlanFeature> features;
  private EntitlementEnforcementMode enforcement;
  private Integer userLimit;
  private Integer userCount;
  private Integer ocrLimit;
  private Integer ocrUsed;
  /** Purchased scan credits left; used after the monthly quota. */
  private Integer ocrTopUpRemaining;
  /** Codes of live add-ons (OCR top-ups excluded). */
  private List<String> addOns;
  private Instant expiresAt;
}
