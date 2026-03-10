package com.inventory.product.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Response DTO for refund processing.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RefundResponse {
  /**
   * Refund ID (unique identifier for this refund).
   */
  private String refundId;

  /**
   * Purchase ID that was refunded.
   */
  private String purchaseId;

  /**
   * List of refunded items with details.
   */
  private List<RefundedItem> refundedItems;

  /**
   * Total refund amount calculated.
   */
  private BigDecimal refundAmount;

  /**
   * Number of items refunded.
   */
  private int totalItemsRefunded;

  /**
   * Timestamp when the refund was created.
   */
  private Instant createdAt;

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class RefundedItem {
    /**
     * Inventory ID (lotId) of the refunded item.
     */
    private String inventoryId;

    /**
     * Name of the refunded product.
     */
    private String name;

    /**
     * Quantity refunded.
     */
    private Integer quantity;

    /**
     * Selling price per unit at time of purchase.
     */
    private BigDecimal priceToRetail;

    /**
     * Total refund amount for this item (priceToRetail * quantity).
     */
    private BigDecimal itemRefundAmount;
  }
}

