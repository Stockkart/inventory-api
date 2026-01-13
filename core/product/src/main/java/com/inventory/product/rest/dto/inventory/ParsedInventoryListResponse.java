package com.inventory.product.rest.dto.inventory;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response DTO for parsed inventory items from invoice image.
 * Returns items in CreateInventoryItemRequest format ready for bulk creation.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ParsedInventoryListResponse {
  private List<CreateInventoryItemRequest> items;
  private int totalItems;
}

