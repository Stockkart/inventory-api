package com.inventory.plan.payment.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class VerifyPaymentCommand {
  private String providerOrderId;
  private String providerPaymentId;
  private String signature;
}
