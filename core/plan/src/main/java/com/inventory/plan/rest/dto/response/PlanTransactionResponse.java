package com.inventory.plan.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlanTransactionResponse {

  private String id;
  private String shopId;
  private String planId;
  private String planName;
  private BigDecimal amount;
  private Integer durationMonths;
  private String paymentMethod;
  private Instant createdAt;
}
