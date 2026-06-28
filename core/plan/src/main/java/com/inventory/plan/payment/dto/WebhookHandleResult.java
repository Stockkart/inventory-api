package com.inventory.plan.payment.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class WebhookHandleResult {
  private boolean processed;
  private String providerOrderId;
  private String providerPaymentId;
  private String paymentMethod;
}
