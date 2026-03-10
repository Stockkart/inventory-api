package com.inventory.product.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

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

  /**
   * Optional reason for refund.
   */
  private String reason;

  /**
   * Timestamp when refund was created.
   */
  private Instant createdAt;
}

