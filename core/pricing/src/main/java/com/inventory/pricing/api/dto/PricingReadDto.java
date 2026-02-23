package com.inventory.pricing.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/** Pricing data returned from the pricing service. API contract. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PricingReadDto {
  private BigDecimal maximumRetailPrice;
  private BigDecimal costPrice;
  private BigDecimal priceToRetail;
  private List<RateDto> rates;
  private String defaultRate;
  private BigDecimal additionalDiscount;
  private String sgst;
  private String cgst;

  public boolean isEmpty() {
    return maximumRetailPrice == null && costPrice == null && priceToRetail == null
        && (rates == null || rates.isEmpty())
        && additionalDiscount == null && sgst == null && cgst == null;
  }

  /** Effective price: defaultRate's price when found in rates, else priceToRetail. */
  public BigDecimal getEffectivePrice() {
    if (org.springframework.util.StringUtils.hasText(defaultRate) && rates != null && !rates.isEmpty()) {
      return rates.stream()
          .filter(r -> defaultRate.equals(r.getName()))
          .map(RateDto::getPrice)
          .findFirst()
          .orElse(priceToRetail);
    }
    return priceToRetail;
  }
}
