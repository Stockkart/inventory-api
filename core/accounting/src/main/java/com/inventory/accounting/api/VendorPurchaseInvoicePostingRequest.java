package com.inventory.accounting.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Builder;

/**
 * Caller-supplied numbers for posting a vendor purchase invoice. All money fields are pre-tax
 * unless named otherwise; tax fields are positive amounts whose sign is handled by the posting
 * service. The vendor purchase invoice id ({@code sourceId}) is the idempotency key.
 */
@Builder
public record VendorPurchaseInvoicePostingRequest(
    String sourceId,
    String invoiceNo,
    LocalDate txnDate,
    String vendorId,
    String vendorDisplayName,
    /** Sum of line cost × quantity (capitalized into Inventory under perpetual). */
    BigDecimal goodsValue,
    BigDecimal inputCgst,
    BigDecimal inputSgst,
    BigDecimal inputIgst,
    BigDecimal shippingCharge,
    BigDecimal otherCharges,
    /** Total invoice amount (after tax + shipping + round-off). */
    BigDecimal invoiceTotal,
    /** Portion paid at the time of invoice; remainder is recorded against Sundry Creditors. */
    BigDecimal paidAmount,
    /**
     * Tender used for the {@code paidAmount} portion: {@code CASH} credits Cash on Hand; {@code UPI},
     * {@code BANK} and {@code CARD} credit Bank; {@code CREDIT} (or null with zero paidAmount) means
     * nothing is paid up-front and the whole invoice rolls into Sundry Creditors.
     */
    String paymentMethod,
    /** Positive = round-off loss (Dr 5500); negative = round-off gain (Cr 2300). */
    BigDecimal roundOff) {}
