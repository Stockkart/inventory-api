package com.inventory.plan.rest.dto.plan;

import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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
