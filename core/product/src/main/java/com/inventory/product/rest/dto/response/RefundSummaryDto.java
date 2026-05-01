package com.inventory.product.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Summary DTO for refund listing.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RefundSummaryDto {
  /**
   * Refund ID.
   */
  private String refundId;

  /**
   * Credit note number (e.g. CN-00001).
   */
  private String creditNoteNo;

  /**
   * Purchase ID that was refunded.
   */
  private String purchaseId;

  /**
   * Invoice number from the purchase.
   */
  private String invoiceNo;

  /**
   * Customer ID (if available).
   */
  private String customerId;

  /**
   * Customer name (if available).
   */
  private String customerName;

  /**
   * Customer phone (if available).
   */
  private String customerPhone;

  /**
   * Customer email (if available).
   */
  private String customerEmail;

  /**
   * Total refund amount.
   */
  private BigDecimal refundAmount;

  /**
   * Number of items refunded.
   */
  private Integer totalItemsRefunded;

  /** Per-product lines (when persisted on document). */
  private List<RefundSummaryItemDto> refundedItems;

  /**
   * Optional reason for refund.
   */
  private String reason;

  /**
   * Timestamp when refund was created.
   */
  private Instant createdAt;
}

