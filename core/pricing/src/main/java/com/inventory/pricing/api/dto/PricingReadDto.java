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
  private BigDecimal sellingPrice;
  private BigDecimal additionalDiscount;
  private String sgst;
  private String cgst;

  public boolean isEmpty() {
    return maximumRetailPrice == null && costPrice == null && priceToRetail == null
        && (rates == null || rates.isEmpty())
        && additionalDiscount == null && sgst == null && cgst == null;
  }

  /** Effective selling price. Returns sellingPrice when set, else from defaultRate: maximumRetailPrice, priceToRetail, costPrice, or rate name. */
  public BigDecimal getEffectivePrice() {
    if (sellingPrice != null) {
      return sellingPrice;
    }
    if (!org.springframework.util.StringUtils.hasText(defaultRate)) {
      return priceToRetail;
    }
    String dr = defaultRate.trim();
    if ("maximumRetailPrice".equalsIgnoreCase(dr)) {
      return maximumRetailPrice != null ? maximumRetailPrice : priceToRetail;
    }
    if ("costPrice".equalsIgnoreCase(dr)) {
      return costPrice != null ? costPrice : priceToRetail;
    }
    if ("priceToRetail".equalsIgnoreCase(dr)) {
      return priceToRetail;
    }
    if (rates != null && !rates.isEmpty()) {
      return rates.stream()
          .filter(r -> dr.equals(r.getName()))
          .map(RateDto::getPrice)
          .findFirst()
          .orElse(priceToRetail);
    }
    return priceToRetail;
  }
}
