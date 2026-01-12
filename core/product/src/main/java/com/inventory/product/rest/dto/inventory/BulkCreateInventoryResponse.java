package com.inventory.product.rest.dto.inventory;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response DTO for bulk inventory creation.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BulkCreateInventoryResponse {
  private List<InventoryReceiptResponse> items;
  private int totalCreated;
  private int totalFailed;
}

