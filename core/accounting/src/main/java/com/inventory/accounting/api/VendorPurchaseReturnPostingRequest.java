package com.inventory.accounting.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Builder;
import lombok.Data;

/** Caller-supplied numbers for posting a vendor purchase return ({@code vendor_purchase_returns}). */
@Data
@Builder
public class VendorPurchaseReturnPostingRequest {

  private String sourceId;
  private String supplierCreditNoteNo;
  private LocalDate txnDate;

  private String vendorId;
  private String vendorDisplayName;
  private String originalInvoiceId;
  private String originalInvoiceNo;

  private BigDecimal goodsValue;
  private BigDecimal inputCgst;
  private BigDecimal inputSgst;
  private BigDecimal inputIgst;

  private BigDecimal returnTotal;
  private BigDecimal roundOff;

  /** Cash received back from supplier now (Dr Cash/Bank). */
  private BigDecimal refundCash;
  private BigDecimal refundOnline;
  /** Portion that reduces vendor payable (Dr Sundry Creditors). */
  private BigDecimal refundToCredit;

  private String paymentMethod;
}
