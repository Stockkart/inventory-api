package com.inventory.plan.rest.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Pricing snapshot for display. Never an authority on price: checkout re-prices the cart server-side.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuoteResponse {

  private List<QuoteItem> items;
  private BigDecimal subtotal;
  private BigDecimal discountTotal;
  private BigDecimal walletCredit;
  /** Prices include tax; the tax component is not itemised until the tax decision (§26.2) lands. */
  private boolean taxInclusive;
  private BigDecimal grandTotal;
  private String currency;
  private int durationMonths;
  private int pricingVersion;
  private Instant quotedAt;
  private Instant expiresAt;

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class QuoteItem {
    /** PLAN, ADDON or OCR_TOPUP. */
    private String type;
    private String code;
    private String name;
    private int quantity;
    private BigDecimal unitPrice;
    private BigDecimal discount;
    private BigDecimal lineTotal;
    /** MANUAL, or VOUCHER when a voucher added the line. */
    private String itemSource;
    /** Voucher that added or discounted this line. */
    private String voucherCode;
    /** Wallet credit applied to this line; already included in the quote's {@code walletCredit}. */
    private BigDecimal walletCredit;
  }
}
