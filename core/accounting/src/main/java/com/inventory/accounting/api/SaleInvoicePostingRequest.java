package com.inventory.accounting.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Builder;
import lombok.Data;

/**
 * Caller-supplied numbers for posting a completed sale ({@code purchases} document). The sale id
 * ({@code sourceId}) is the idempotency key. Tax fields are positive output-tax amounts; {@code
 * taxableRevenue} is the net taxable value before output GST (matches {@code revenueBeforeTax} on
 * the sale).
 */
@Data
@Builder
public class SaleInvoicePostingRequest {

  private String sourceId;
  private String invoiceNo;
  private LocalDate txnDate;

  private String customerId;
  private String customerDisplayName;

  /** Net taxable sales (revenue before output tax). */
  private BigDecimal taxableRevenue;

  private BigDecimal outputCgst;
  private BigDecimal outputSgst;
  private BigDecimal outputIgst;

  /** Total amount due from customer (after tax and bill-level round-off). */
  private BigDecimal saleTotal;

  /** Cash collected at checkout (Dr Cash on Hand). */
  private BigDecimal paidCash;

  /** Online / UPI / card collected at checkout (Dr Bank). */
  private BigDecimal paidOnline;

  /** Amount on customer credit / Sundry Debtors. When null, derived from sale total − tenders. */
  private BigDecimal receivableAmount;

  /**
   * Legacy single-tender paid total. Used only when {@code paidCash}/{@code paidOnline} are not
   * supplied; routed via {@code paymentMethod}.
   */
  private BigDecimal paidAmount;

  /**
   * Canonical checkout method ({@code CASH}, {@code ONLINE}, {@code CREDIT}, {@code CASH_ONLINE},
   * etc.) or legacy {@code UPI}/{@code BANK}/{@code CARD}. Used for legacy {@code paidAmount}
   * routing and narration.
   */
  private String paymentMethod;

  /** Cost of goods sold (sum of line cost totals). Perpetual inventory relief. */
  private BigDecimal cogsAmount;

  /**
   * {@code saleTotal − (taxableRevenue + outputCgst + outputSgst + outputIgst)}. Positive = bill
   * round-up (Dr round-off expense); negative = round-down (Cr round-off payable).
   */
  private BigDecimal roundOff;
}
