package com.inventory.plan.rest.dto.request;

import com.inventory.common.entitlement.PlanFeature;
import com.inventory.plan.domain.model.AddOnBillingType;
import com.inventory.plan.domain.model.AddOnGrantType;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Create or full edit. {@code code} and {@code grantType} are fixed after create. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddOnAdminRequest {

  private String code;
  private String name;
  private String description;
  private BigDecimal price;
  private AddOnBillingType billingType;
  private AddOnGrantType grantType;
  private PlanFeature grantsFeature;
  private Integer grantsQuantity;
  private boolean stackable;
  private Integer maxQuantity;
  private Integer displayOrder;
}
