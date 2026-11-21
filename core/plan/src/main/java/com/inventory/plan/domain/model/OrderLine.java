package com.inventory.plan.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * One priced line of an order, as the server built it. Stored on the order and its transaction so
 * the charge can be explained later under the pricing rules of the day.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderLine {

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
  /** Voucher that added or discounted this line; one voucher per line. */
  private String voucherCode;
  /** Wallet credit applied to this line, on top of {@code lineTotal}. Feeds the referral reward base (§24). */
  private BigDecimal walletCredit;

  // What an add-on line grants, copied from the catalogue when the order was priced, so a later
  // catalogue edit cannot change what was paid for. Null on plan lines and on older orders (the
  // grant then reads the catalogue as before).
  private AddOnGrantType grantType;
  private com.inventory.common.entitlement.PlanFeature grantsFeature;
  private Integer grantsQuantity;
  private AddOnBillingType billingType;
}
