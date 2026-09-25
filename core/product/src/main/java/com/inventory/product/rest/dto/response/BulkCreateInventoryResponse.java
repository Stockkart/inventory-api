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

  /** Present when vendor purchase created/updated vendor credit ledger due. */
  private String creditEntryId;

  @JsonProperty("createdCount")
  public int getCreatedCount() {
    return totalCreated;
  }

  /** Per-item failure messages when {@link #totalFailed} &gt; 0 (product name + reason). */
  private List<String> itemErrors;

  /**
   * Advisory per-item notes about lines that were created anyway — today, a cost that is not
   * below the price the line will sell at. Nothing here failed; the goods arrived and are
   * recorded. Raised on the response so the operator can settle it while the bill is still in
   * hand rather than discovering it in the cart weeks later.
   */
  private List<String> itemWarnings;
}

