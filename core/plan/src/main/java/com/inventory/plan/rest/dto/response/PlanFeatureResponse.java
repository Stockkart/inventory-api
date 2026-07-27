package com.inventory.plan.rest.dto.response;

import com.inventory.plan.domain.model.FeatureAvailability;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One capability row of a plan, as returned by {@code GET /plans}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlanFeatureResponse {

  private String key;
  private String label;
  private FeatureAvailability availability;
  private Integer sortOrder;
}
