package com.inventory.accounting.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Builder;

/** Caller-supplied numbers for posting a vendor purchase return ({@code vendor_purchase_returns}). */
@Builder
public record VendorPurchaseReturnPostingRequest(
    String sourceId,
    String supplierCreditNoteNo,
    LocalDate txnDate,
    String vendorId,
    String vendorDisplayName,
    String originalInvoiceId,
    String originalInvoiceNo,
    BigDecimal goodsValue,
    BigDecimal inputCgst,
    BigDecimal inputSgst,
    BigDecimal inputIgst,
    BigDecimal returnTotal,
    BigDecimal roundOff,
    /** Cash received back from supplier now (Dr Cash/Bank). */
    BigDecimal refundCash,
    BigDecimal refundOnline,
    /** Portion that reduces vendor payable (Dr Sundry Creditors). */
    BigDecimal refundToCredit,
    String paymentMethod) {}
