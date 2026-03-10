package com.inventory.pricing.rest.dto;

import com.inventory.pricing.utils.PricingUtils;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/** Pricing data returned from the pricing service. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PricingReadDto {
  private BigDecimal maximumRetailPrice;
  private BigDecimal costPrice;
  private BigDecimal priceToRetail;
  private List<RateDto> rates;
  private String defaultRate;
  private BigDecimal sellingPrice;
  private BigDecimal additionalDiscount;
  private String sgst;
  private String cgst;

  public boolean isEmpty() {
    return maximumRetailPrice == null && costPrice == null && priceToRetail == null
        && (rates == null || rates.isEmpty())
        && additionalDiscount == null && sgst == null && cgst == null;
  }

  /** Effective selling price. Returns sellingPrice when set, else from defaultRate. */
  public BigDecimal getEffectivePrice() {
    return PricingUtils.resolveEffectivePriceFromReadDto(this);
  }
}
