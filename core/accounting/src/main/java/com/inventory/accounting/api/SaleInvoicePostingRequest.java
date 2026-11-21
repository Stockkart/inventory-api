package com.inventory.accounting.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Builder;

/**
 * Caller-supplied numbers for posting a completed sale ({@code purchases} document). The sale id
 * ({@code sourceId}) is the idempotency key. Tax fields are positive output-tax amounts; {@code
 * taxableRevenue} is the net taxable value before output GST (matches {@code revenueBeforeTax} on
 * the sale).
 */
@Builder
public record SaleInvoicePostingRequest(
    String sourceId,
    String invoiceNo,
    LocalDate txnDate,
    String customerId,
    String customerDisplayName,
    /** Net taxable sales (revenue before output tax). */
    BigDecimal taxableRevenue,
    BigDecimal outputCgst,
    BigDecimal outputSgst,
    BigDecimal outputIgst,
    /** Total amount due from customer (after tax and bill-level round-off). */
    BigDecimal saleTotal,
    /** Cash collected at checkout (Dr Cash on Hand). */
    BigDecimal paidCash,
    /** Online / UPI / card collected at checkout (Dr Bank). */
    BigDecimal paidOnline,
    /** Amount on customer credit / Sundry Debtors. When null, derived from sale total − tenders. */
    BigDecimal receivableAmount,
    /**
     * Legacy single-tender paid total. Used only when {@code paidCash}/{@code paidOnline} are not
     * supplied; routed via {@code paymentMethod}.
     */
    BigDecimal paidAmount,
    /**
     * Canonical checkout method ({@code CASH}, {@code ONLINE}, {@code CREDIT}, {@code CASH_ONLINE},
     * etc.) or legacy {@code UPI}/{@code BANK}/{@code CARD}. Used for legacy {@code paidAmount}
     * routing and narration.
     */
    String paymentMethod,
    /** Cost of goods sold (sum of line cost totals). Perpetual inventory relief. */
    BigDecimal cogsAmount,
    /**
     * {@code saleTotal − (taxableRevenue + outputCgst + outputSgst + outputIgst)}. Positive = bill
     * round-up (Dr round-off expense); negative = round-down (Cr round-off payable).
     */
    BigDecimal roundOff) {}
