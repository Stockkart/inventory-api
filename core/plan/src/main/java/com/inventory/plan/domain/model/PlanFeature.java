package com.inventory.plan.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One row of a plan's capability list, embedded in {@link Plan}.
 *
 * <p>The label is stored alongside the key so the pricing page can gain a feature row without a
 * frontend release. {@code key} is the stable identity — it is what clients align matrix rows on
 * across plans, and what entitlement checks should read.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlanFeature {

  /** Stable identifier, e.g. {@code CREDIT_BALANCE}. Never shown to users. */
  private String key;

  /** Display label, e.g. "Credit Balance". */
  private String label;

  private FeatureAvailability availability;

  /** Ascending display order. Rows without one sort after those with one. */
  private Integer sortOrder;
}
