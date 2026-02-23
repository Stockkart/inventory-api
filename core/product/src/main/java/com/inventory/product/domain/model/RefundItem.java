package com.inventory.product.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Represents an item that was refunded.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RefundItem {

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

