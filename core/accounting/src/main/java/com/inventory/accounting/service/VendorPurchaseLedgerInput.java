package com.inventory.accounting.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Minimal invoice snapshot passed from inventory bulk registration — keeps accounting independent
 * of the product persistence model.
 */
public record VendorPurchaseLedgerInput(
    String invoiceMongoId,
    String vendorId,
    String invoiceNo,
    Instant invoiceDate,
    Instant createdAt,
    BigDecimal lineSubTotal,
    BigDecimal taxTotal,
    BigDecimal shippingCharge,
    BigDecimal otherCharges,
    BigDecimal roundOff,
    BigDecimal invoiceTotal,
    List<Line> lines,
    /** Display name from vendor master (optional; improves GL account label). */
    String vendorDisplayName) {

  /**
   * Mirrors saved vendor invoice lines; unit value for postings is typically {@code costPrice} when
   * &gt; 0, else {@code priceToRetail} (PTR), matching frontend purchase valuation.
   */
  public record Line(Integer count, BigDecimal costPrice, BigDecimal priceToRetail) {}
}
