package com.inventory.plan.rest.dto.response;

import com.inventory.plan.domain.model.RefundSource;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminPlanOrderResponse {
  private String id;
  private String shopId;
  private String planCode;
  private String status;
  private String provider;
  private String providerPaymentId;
  private BigDecimal amount;
  private BigDecimal walletCredit;
  private BigDecimal refundedAmount;
  private Instant paidAt;
  private Instant fulfilledAt;
  private Instant refundedAt;
  private RefundSource refundSource;
  private String refundReason;
  private String failureReason;
}
