package com.inventory.product.rest.dto.request;

import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Optional vendor invoice header for bulk stock registration. When omitted, behavior is unchanged.
 */
@Data
public class VendorPurchaseInvoiceRequest {

  private String invoiceNo;
  private Instant invoiceDate;
  private BigDecimal lineSubTotal;
  private BigDecimal taxTotal;
  private BigDecimal shippingCharge;
  private BigDecimal otherCharges;
  private BigDecimal roundOff;
  private BigDecimal invoiceTotal;
  /** CASH | ONLINE | CREDIT | CASH_ONLINE | ONLINE_CREDIT | CREDIT_CASH (defaults to CASH). */
  private String paymentMethod;
  /** Optional paid-now amount for split credit purchases. */
  private BigDecimal paidAmount;
  /** For combination modes: breakdown per sub-method e.g. {CASH: 600, ONLINE: 400}. */
  private Map<String, BigDecimal> splitAmounts;
  /** User-selected bank GL account code for online payments (e.g. BANK-AXIS). Falls back to BANK. */
  private String bankGlAccountCode;
}
