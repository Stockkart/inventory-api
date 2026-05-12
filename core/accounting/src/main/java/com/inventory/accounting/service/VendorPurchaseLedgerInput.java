package com.inventory.accounting.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

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
    String vendorDisplayName,
    /** CASH | ONLINE | CREDIT | CASH_ONLINE | ONLINE_CREDIT | CREDIT_CASH */
    String paymentMethod,
    /** Amount paid immediately (for resolving split). */
    BigDecimal paidAmount,
    /** For combination modes: breakdown per sub-method. */
    Map<String, BigDecimal> splitAmounts,
    /** User-selected bank GL account code for online payments (nullable — falls back to BANK). */
    String bankGlAccountCode) {

  /** Backward-compatible constructor without payment fields. */
  public VendorPurchaseLedgerInput(
      String invoiceMongoId, String vendorId, String invoiceNo,
      Instant invoiceDate, Instant createdAt, BigDecimal lineSubTotal,
      BigDecimal taxTotal, BigDecimal shippingCharge, BigDecimal otherCharges,
      BigDecimal roundOff, BigDecimal invoiceTotal, List<Line> lines,
      String vendorDisplayName) {
    this(invoiceMongoId, vendorId, invoiceNo, invoiceDate, createdAt,
        lineSubTotal, taxTotal, shippingCharge, otherCharges, roundOff,
        invoiceTotal, lines, vendorDisplayName, null, null, null, null);
  }

  /** Constructor without bankGlAccountCode for backward compatibility. */
  public VendorPurchaseLedgerInput(
      String invoiceMongoId, String vendorId, String invoiceNo,
      Instant invoiceDate, Instant createdAt, BigDecimal lineSubTotal,
      BigDecimal taxTotal, BigDecimal shippingCharge, BigDecimal otherCharges,
      BigDecimal roundOff, BigDecimal invoiceTotal, List<Line> lines,
      String vendorDisplayName, String paymentMethod, BigDecimal paidAmount,
      Map<String, BigDecimal> splitAmounts) {
    this(invoiceMongoId, vendorId, invoiceNo, invoiceDate, createdAt,
        lineSubTotal, taxTotal, shippingCharge, otherCharges, roundOff,
        invoiceTotal, lines, vendorDisplayName, paymentMethod, paidAmount,
        splitAmounts, null);
  }

  /**
   * Mirrors saved vendor invoice lines; unit value for postings is typically {@code costPrice} when
   * &gt; 0, else {@code priceToRetail} (PTR), matching frontend purchase valuation.
   */
  public record Line(Integer count, BigDecimal costPrice, BigDecimal priceToRetail) {}
}
