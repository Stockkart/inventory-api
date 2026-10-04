package com.inventory.product.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** One inventory line on a supplier purchase return (history view). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VendorPurchaseReturnLineSummaryDto {

  private String inventoryId;

  /** From inventory or invoice line when inventory is missing. */
  private String productName;

  private String barcode;

  /**
   * Quantity returned in invoice / POS (sell) units — derived from stored base qty ÷ pack factor.
   */
  private BigDecimal displayQuantityReturned;

  /** Persisted canonical base units on the debit note (for audit; omit from UI if not needed). */
  private Integer baseQuantityReturned;

  private BigDecimal taxableValue;

  private BigDecimal centralGstAmount;

  private BigDecimal stateGstAmount;

  /** Line total incl. tax snapshot. */
  private BigDecimal lineNoteValue;

  // --- The purchase this line reverses, in the terms the bill stated it ---
  /** Cost per unit as billed, and the price to retail beside it. */
  private BigDecimal costPrice;
  private BigDecimal priceToRetail;
  private BigDecimal maximumRetailPrice;
  /** Total GST rate on the line (sgst + cgst, or the igst rate). */
  private BigDecimal gstRatePct;
  /** IGST where the supplier is in another state; the halves above are then zero. */
  private BigDecimal integratedGstAmount;
  /** Scheme and additional discount the goods were bought under. */
  private String purchaseSchemeType;
  private Integer purchaseSchemePayFor;
  private Integer purchaseSchemeFree;
  private BigDecimal purchaseSchemePercentage;
  private BigDecimal purchaseAdditionalDiscount;
}
