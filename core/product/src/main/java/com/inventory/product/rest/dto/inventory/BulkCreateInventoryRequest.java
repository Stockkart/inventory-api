package com.inventory.product.rest.dto.inventory;

import lombok.Data;

import java.util.List;

/**
 * Request DTO for bulk inventory creation.
 * vendorId and lotId are shared across all items in the list.
 */
@Data
public class BulkCreateInventoryRequest {
  // Shared fields applied to all items
  private String vendorId;
  /** When true, record this purchase as credit (buyer owes vendor). When false, treated as paid/cash. */
  private Boolean onCredit;
  /** When vendor is a StockKart user and purchase is on credit, assign to vendor's shop. */
  private String vendorShopId;
  private String lotId;
  
  // List of inventory items (without vendorId and lotId)
  private List<CreateInventoryItemRequest> items;
}

