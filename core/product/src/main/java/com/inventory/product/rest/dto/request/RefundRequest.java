package com.inventory.product.rest.dto.request;

import lombok.Data;

import java.util.List;

/**
 * Request DTO for processing refunds.
 * Supports partial refunds by specifying items and quantities to refund.
 */
@Data
public class RefundRequest {
  /**
   * Purchase ID to verify which purchase the refund belongs to.
   */
  private String purchaseId;

  /**
   * List of items to refund with quantities.
   * Partial refunds are supported - only specify items and quantities to refund.
   */
  private List<RefundItem> items;

  /**
   * Optional reason or notes for the refund.
   */
  private String reason;

  @Data
  public static class RefundItem {
    /**
     * Inventory ID (lotId) of the item to refund.
     */
    private String inventoryId;

    /**
     * Quantity to refund. Must not exceed the quantity purchased.
     */
    private Integer quantity;
  }
}

