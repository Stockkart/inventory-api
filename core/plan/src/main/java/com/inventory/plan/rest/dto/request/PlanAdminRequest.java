package com.inventory.plan.rest.dto.request;

import com.inventory.common.entitlement.PlanFeature;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Full plan definition for create and edit. {@code code} is set on create and ignored on edit.
 * Null limits mean unlimited.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlanAdminRequest {

  private String code;
  private String planName;
  private BigDecimal arcPrice;
  private BigDecimal price;
  private BigDecimal billingLimit;
  private Integer billCountLimit;
  private Integer smsLimit;
  private Integer whatsappLimit;
  private Integer userLimit;
  private Integer ocrLimit;
  private boolean unlimited;
  private Set<PlanFeature> features;
  private Integer displayOrder;
  private String badge;
  private String bestFor;
  private String linkedId;
}
