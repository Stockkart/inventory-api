package com.inventory.product.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * One inventory line reduced on a vendor purchase return.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VendorPurchaseReturnItem {

  private String inventoryId;

  /** Quantity returned (base units — same canonical unit as inventory currentBaseCount). */
  private Integer baseQuantityReturned;

  /** Taxable portion for this slice (persisted snapshot for auditing). */
  private BigDecimal taxableValue;

  private BigDecimal centralTaxAmount;
  private BigDecimal stateUtTaxAmount;
  private BigDecimal lineNoteValue;
}
