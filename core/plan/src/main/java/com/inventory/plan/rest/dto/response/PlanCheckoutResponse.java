package com.inventory.plan.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlanCheckoutResponse {

  private String orderId;
  private String provider;
  private BigDecimal amount;
  private String currency;
  private String planName;
  private RazorpayPayload razorpay;

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class RazorpayPayload {
    private String keyId;
    private String orderId;
  }
}
