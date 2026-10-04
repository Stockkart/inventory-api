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
  /** IGST, credited instead of the two halves when the supplier is in another state. */
  private BigDecimal integratedTaxAmount;
  private BigDecimal lineNoteValue;

  // --- As the goods were bought ----------------------------------------------
  // A debit note reverses a purchase, and it is filed by restating that purchase: the same
  // goods, the same cost, the same scheme and discount, the same rate. The line recorded a
  // quantity and an amount and nothing else -- not even the product's name -- so the note could
  // not be read against the bill it reverses without going back to the invoice for every field.

  /** Product name as it stood on the purchase invoice line. */
  private String name;
  /** Quantity in the invoice's own units, which is how a bill is read. */
  private BigDecimal displayQuantityReturned;
  /** Cost per unit as billed, before the reductions below. */
  private BigDecimal costPrice;
  /** Price to retail, as the purchase entry shows it. */
  private BigDecimal priceToRetail;
  private BigDecimal maximumRetailPrice;
  /** Total GST rate on the line (sgst + cgst, or the igst rate). */
  private BigDecimal gstRatePct;
  /** Purchase scheme and additional discount the goods were bought under. */
  private String purchaseSchemeType;
  private Integer purchaseSchemePayFor;
  private Integer purchaseSchemeFree;
  private BigDecimal purchaseSchemePercentage;
  private BigDecimal purchaseAdditionalDiscount;
}
