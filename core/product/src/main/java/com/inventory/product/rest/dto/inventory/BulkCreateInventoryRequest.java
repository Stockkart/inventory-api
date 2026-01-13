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
  private String lotId;
  
  // List of inventory items (without vendorId and lotId)
  private List<CreateInventoryItemRequest> items;
}

