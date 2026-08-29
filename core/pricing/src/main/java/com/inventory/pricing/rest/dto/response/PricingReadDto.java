package com.inventory.pricing.rest.dto.response;

import com.inventory.pricing.utils.PricingUtils;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PricingReadDto {
  private BigDecimal maximumRetailPrice;
  private BigDecimal costPrice;
  private BigDecimal effectiveCostPrice;
  private BigDecimal priceToRetail;
  private List<RateDto> rates;
  private String defaultRate;
  private BigDecimal sellingPrice;
  private BigDecimal saleAdditionalDiscount;
  private BigDecimal purchaseAdditionalDiscount;
  private SchemeDto purchaseScheme;
  private SchemeDto saleScheme;
  private String sgst;
  private String cgst;

  public boolean isEmpty() {
    return maximumRetailPrice == null && costPrice == null && priceToRetail == null
        && (rates == null || rates.isEmpty())
        && saleAdditionalDiscount == null && sgst == null && cgst == null;
  }

  public BigDecimal getEffectivePrice() {
    return PricingUtils.resolveEffectivePriceFromReadDto(this);
  }

  /**
   * Landed cost per unit. Records written before effectiveCostPrice existed have it derived on the
   * fly, so historical margins read correctly without a backfill.
   */
  public BigDecimal resolveEffectiveCostPrice() {
    if (effectiveCostPrice != null) {
      return effectiveCostPrice;
    }
    return PricingUtils.computeEffectiveCostPrice(
        costPrice,
        purchaseAdditionalDiscount,
        purchaseScheme == null
            ? null
            : new com.inventory.pricing.domain.model.Scheme(
                purchaseScheme.getSchemeType(),
                purchaseScheme.getSchemePayFor(),
                purchaseScheme.getSchemeFree(),
                purchaseScheme.getSchemePercentage()));
  }
}
