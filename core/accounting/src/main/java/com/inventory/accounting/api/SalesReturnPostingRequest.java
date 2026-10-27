package com.inventory.accounting.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Builder;

/** Caller-supplied numbers for posting a customer sales return / credit note ({@code refunds}). */
@Builder
public record SalesReturnPostingRequest(
    String sourceId,
    String creditNoteNo,
    LocalDate txnDate,
    String customerId,
    String customerDisplayName,
    String originalSaleId,
    String originalInvoiceNo,
    BigDecimal taxableRevenue,
    BigDecimal outputCgst,
    BigDecimal outputSgst,
    BigDecimal outputIgst,
    BigDecimal returnTotal,
    BigDecimal cogsAmount,
    BigDecimal roundOff,
    BigDecimal refundCash,
    BigDecimal refundOnline,
    /** Portion that reduces customer receivable (Cr Sundry Debtors). */
    BigDecimal refundToCredit,
    String paymentMethod) {}
