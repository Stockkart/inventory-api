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
}
