package com.inventory.plan.payment.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CreateCheckoutResult {
  private String providerOrderId;
  private String publicKey;
}
