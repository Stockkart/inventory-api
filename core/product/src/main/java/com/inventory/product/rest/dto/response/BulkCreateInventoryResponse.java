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
   * How the invoice header the operator typed compares to what its lines come to.
   *
   * <p>{@code OK} when the two agree. {@code MISSING} when no header was given, {@code MISMATCH}
   * when the stated subtotal and tax do not agree at the line rates, and {@code RATE_CONFLICT}
   * when the tax stated implies a GST slab none of the goods are priced at -- which is a product
   * on the wrong rate rather than a mis-typed total.
   *
   * <p>Advisory. The stock is registered either way, and the invoice keeps the header as typed;
   * this is here so the client can put the discrepancy in front of the person still holding the
   * bill, which is the only moment it is cheap to settle. Null when no invoice header was sent.
   */
  private String headerReconciliation;

  /** Taxable value the lines resolve to, for showing beside the typed subtotal. */
  private java.math.BigDecimal computedLineSubTotal;

  /** Tax the lines resolve to at their own rates, for showing beside the typed tax. */
  private java.math.BigDecimal computedTaxTotal;

  /**
   * Products whose GST rate disagrees with the rest of the shop's catalogue under the same HSN.
   *
   * <p>The one error class no total can reveal: an invoice priced at the wrong slab adds up
   * perfectly against its own bill and is still wrong. Advisory; nothing is blocked.
   */
  private List<String> rateWarnings;
}

