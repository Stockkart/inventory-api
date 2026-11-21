package com.inventory.plan.domain.model;

import com.inventory.common.entitlement.PlanFeature;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What a paid term includes, copied from the plan when the term is granted, so a later catalogue
 * edit does not change what the shop already paid for. Null on subscriptions granted before this
 * field existed; those read the live plan as before.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanTermsSnapshot {

  private String planId;
  private String planCode;
  private Set<PlanFeature> features;
  private Integer userLimit;
  private Integer ocrLimit;
  private boolean unlimited;
}
