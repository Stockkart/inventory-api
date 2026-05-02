package com.inventory.product.rest.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
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
  /** Number of successfully created inventory rows (alias: createdCount for API clients). */
  private int totalCreated;
  private int totalFailed;
  /**
   * Same as {@link #vendorPurchaseInvoiceId}; kept for older clients that read {@code lotId}.
   */
  private String lotId;
  /** Present when {@code vendorPurchaseInvoice} was sent and at least one line was created. */
  private String vendorPurchaseInvoiceId;

  /** Present when a PURCHASE journal was posted for {@link #vendorPurchaseInvoiceId}. */
  private String accountingJournalEntryId;

  @JsonProperty("createdCount")
  public int getCreatedCount() {
    return totalCreated;
  }
}

