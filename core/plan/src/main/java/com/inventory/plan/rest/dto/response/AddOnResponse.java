package com.inventory.plan.rest.dto.response;

import com.inventory.common.entitlement.PlanFeature;
import com.inventory.plan.domain.model.AddOnBillingType;
import com.inventory.plan.domain.model.AddOnGrantType;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** Add-on as shown at checkout. Hide FEATURE add-ons whose feature the chosen plan already has. */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class AddOnResponse {

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
