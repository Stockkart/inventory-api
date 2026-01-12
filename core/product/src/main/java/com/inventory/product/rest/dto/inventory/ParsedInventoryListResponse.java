package com.inventory.product.rest.dto.inventory;

import com.inventory.ocr.dto.ParsedInventoryItem;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response DTO for parsed inventory items from invoice image.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ParsedInventoryListResponse {
  private List<ParsedInventoryItem> items;
  private int totalItems;
}

