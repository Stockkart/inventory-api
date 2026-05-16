package com.inventory.accounting.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Builder;
import lombok.Data;

/** Caller-supplied numbers for posting a customer sales return / credit note ({@code refunds}). */
@Data
@Builder
public class SalesReturnPostingRequest {

  private String sourceId;
  private String creditNoteNo;
  private LocalDate txnDate;

  private String customerId;
  private String customerDisplayName;
  private String originalSaleId;
  private String originalInvoiceNo;

  private BigDecimal taxableRevenue;
  private BigDecimal outputCgst;
  private BigDecimal outputSgst;
  private BigDecimal outputIgst;

  private BigDecimal returnTotal;
  private BigDecimal cogsAmount;
  private BigDecimal roundOff;

  private BigDecimal refundCash;
  private BigDecimal refundOnline;
  /** Portion that reduces customer receivable (Cr Sundry Debtors). */
  private BigDecimal refundToCredit;

  private String paymentMethod;
}
