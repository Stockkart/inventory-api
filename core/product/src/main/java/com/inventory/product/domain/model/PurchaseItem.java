package com.inventory.product.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseItem {

  private String inventoryId;
  private String name;
  private Integer quantity;
  private BigDecimal maximumRetailPrice;
  private BigDecimal sellingPrice;
  private BigDecimal discount;
  private BigDecimal additionalDiscount; // Additional discount percentage from inventory
  private BigDecimal totalAmount; // Final amount after additionalDiscount and taxes (CGST + SGST)
  private String sgst; // SGST rate from inventory (e.g., "9" for 9%)
  private String cgst; // CGST rate from inventory (e.g., "9" for 9%)
  /**
   * Scheme type for selling:
   * - FIXED_UNITS: use schemePayFor / schemeFree (e.g. "2 free on 10").
   * - PERCENTAGE: use schemePercentage (e.g. 10 = 10% extra free).
   */
  private SchemeType schemeType;
  /** Scheme for selling: pay for this many (e.g. 10). With schemeFree = 2, "2 free on 10". Billing uses paid quantity. */
  private Integer schemePayFor;
  /** Scheme for selling: free units per batch (e.g. 2). With schemePayFor = 10, "2 free on 10". */
  private Integer schemeFree;
  /** Scheme percentage for selling when schemeType is PERCENTAGE (e.g. 10 = 10% extra free). */
  private BigDecimal schemePercentage;
}

