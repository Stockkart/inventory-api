package com.inventory.accounting.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Builder;
import lombok.Data;

/**
 * Caller-supplied numbers for posting a vendor purchase invoice. All money fields are pre-tax
 * unless named otherwise; tax fields are positive amounts whose sign is handled by the posting
 * service. The vendor purchase invoice id ({@code sourceId}) is the idempotency key.
 */
@Data
@Builder
public class VendorPurchaseInvoicePostingRequest {

  private String sourceId;
  private String invoiceNo;
  private LocalDate txnDate;

  private String vendorId;
  private String vendorDisplayName;

  /** Sum of line cost × quantity (capitalized into Inventory under perpetual). */
  private BigDecimal goodsValue;

  private BigDecimal inputCgst;
  private BigDecimal inputSgst;
  private BigDecimal inputIgst;

  private BigDecimal shippingCharge;
  private BigDecimal otherCharges;

  /** Total invoice amount (after tax + shipping + round-off). */
  private BigDecimal invoiceTotal;

  /** Portion paid at the time of invoice; remainder is recorded against Sundry Creditors. */
  private BigDecimal paidAmount;

  /**
   * Tender used for the {@code paidAmount} portion when split fields are absent: {@code CASH}
   * credits Cash on Hand; {@code UPI}/{@code BANK}/{@code CARD} credit Bank; {@code CREDIT} (or
   * null with zero paidAmount) means the whole invoice rolls into Sundry Creditors.
   */
  private String paymentMethod;

  /** Split tender: cash leg at purchase (preferred over legacy paidAmount). */
  private BigDecimal paidCash;

  /** Split tender: online/bank leg at purchase. */
  private BigDecimal paidOnline;

  /** Split tender: credit / payable leg at purchase. */
  private BigDecimal creditAmount;

  /** Positive = round-off loss (Dr 5500); negative = round-off gain (Cr 2300). */
  private BigDecimal roundOff;
}
