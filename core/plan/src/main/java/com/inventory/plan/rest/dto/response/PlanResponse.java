package com.inventory.plan.rest.dto.response;

import com.inventory.common.entitlement.PlanFeature;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Set;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlanResponse {

  private String id;
  private String planName;
  private BigDecimal price;
  private BigDecimal arcPrice;
  private BigDecimal billingLimit;
  private Integer billCountLimit;
  private Integer smsLimit;
  private Integer whatsappLimit;
  private Integer userLimit;
  private boolean unlimited;
  private String linkedId;
  private String bestFor;
  private String code;
  private Integer displayOrder;
  private Integer ocrLimit;
  /** Staff roles the plan allows. Null = no cap. */
  private Integer roleLimit;
  /** Users on biometric attendance. Null = no cap. */
  private Integer attendanceUserLimit;
  private Set<PlanFeature> features;
  private String badge;
  /** Struck-through anchor shown beside arcPrice. Display only: never charged. Null on legacy plans. */
  private BigDecimal listPrice;
  /** Length of the free trial a new shop gets, in days ({@code plan.trial-days}). */
  private Integer trialDays;
}
