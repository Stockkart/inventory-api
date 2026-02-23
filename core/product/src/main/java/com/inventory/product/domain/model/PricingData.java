package com.inventory.product.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Pricing data DTO. Used for:
 * - Mongo projection when reading legacy pricing from inventory document
 * - Resolver return type (from Pricing module or legacy)
 * - Enricher applies it to Inventory
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PricingData {

  private BigDecimal maximumRetailPrice;
  private BigDecimal costPrice;
  private BigDecimal sellingPrice;
  private BigDecimal additionalDiscount;
  private String sgst;
  private String cgst;

  public boolean isEmpty() {
    return maximumRetailPrice == null && costPrice == null && sellingPrice == null
        && additionalDiscount == null && sgst == null && cgst == null;
  }
}
