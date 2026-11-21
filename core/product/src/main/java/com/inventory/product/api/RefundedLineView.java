package com.inventory.product.api;

import java.math.BigDecimal;

/**
 * One returned line of a refund as other modules see it: what a GST return needs to reverse the
 * sale line it came from, without the stored refund document.
 *
 * @param inventoryId lot the line was sold from; may be null for a line with no lot
 * @param name line name as printed on the credit note
 * @param quantity units returned
 * @param refundAmount amount refunded for the line, tax inclusive; never null
 * @param cgstRate CGST rate the line carried as stored (e.g. "2.5"); may be null on older refunds
 * @param sgstRate SGST rate the line carried as stored; may be null on older refunds
 */
public record RefundedLineView(
    String inventoryId,
    String name,
    int quantity,
    BigDecimal refundAmount,
    String cgstRate,
    String sgstRate) {

  public RefundedLineView {
    refundAmount = refundAmount == null ? BigDecimal.ZERO : refundAmount;
  }
}
