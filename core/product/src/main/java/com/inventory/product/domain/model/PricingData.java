package com.inventory.product.domain.model;

import com.inventory.pricing.domain.model.Rate;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

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
  private List<Rate> rates;
  private String defaultRate;
  private BigDecimal additionalDiscount;
  private String sgst;
  private String cgst;

  public boolean isEmpty() {
    return maximumRetailPrice == null && costPrice == null && sellingPrice == null
        && (rates == null || rates.isEmpty())
        && additionalDiscount == null && sgst == null && cgst == null;
  }

  /** Effective selling price: defaultRate's price when set and found in rates, else sellingPrice. */
  public BigDecimal getEffectiveSellingPrice() {
    if (org.springframework.util.StringUtils.hasText(defaultRate) && rates != null && !rates.isEmpty()) {
      return rates.stream()
          .filter(r -> defaultRate.equals(r.getName()))
          .map(Rate::getPrice)
          .findFirst()
          .orElse(sellingPrice);
    }
    return sellingPrice;
  }
}
