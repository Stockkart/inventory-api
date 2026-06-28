package com.inventory.plan.payment.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class VerifyPaymentResult {
  private boolean valid;
  private String paymentMethod;
}
