package com.inventory.plan.rest.dto.plan;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UsageResponse {

  private String shopId;
  private String month;
  private BigDecimal billingAmountUsed;
  private Integer billCountUsed;
  private Integer smsUsed;
  private Integer whatsappUsed;
}
