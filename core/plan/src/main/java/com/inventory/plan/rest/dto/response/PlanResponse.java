package com.inventory.plan.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

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
}
