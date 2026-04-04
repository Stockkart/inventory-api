package com.inventory.product.rest.dto.response;

import com.inventory.product.rest.dto.request.CreateInventoryItemRequest;
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
  /** Optional header fields when the parser extracts them; otherwise null. */
  private ParsedVendorInvoiceDto vendorPurchaseInvoice;
}

