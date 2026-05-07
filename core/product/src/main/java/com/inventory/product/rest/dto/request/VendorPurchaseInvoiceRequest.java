package com.inventory.product.rest.dto.request;

import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

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
  /** CASH | ONLINE | CREDIT (defaults to CASH when omitted). */
  private String paymentMethod;
  /** Optional paid-now amount for split credit purchases. */
  private BigDecimal paidAmount;
}
