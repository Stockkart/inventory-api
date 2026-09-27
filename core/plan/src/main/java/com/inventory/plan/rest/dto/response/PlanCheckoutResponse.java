package com.inventory.plan.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlanCheckoutResponse {

  private String orderId;
  private String status;
  private String provider;
  /** Amount charged, after discounts and wallet credit. */
  private BigDecimal amount;
  private String currency;
  private String planName;
  private List<QuoteResponse.QuoteItem> items;
  private BigDecimal subtotal;
  private BigDecimal discountTotal;
  private BigDecimal walletCredit;
  /** Pay before this or the order expires and its reservations are released. */
  private Instant expiresAt;
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
