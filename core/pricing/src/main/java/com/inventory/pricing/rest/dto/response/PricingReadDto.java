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
   * Landed cost per unit: the cost after the vendor's purchase scheme and additional discount.
   *
   * <p>Derived from those fields whenever they are there to derive from, and only then falling
   * back to the stored {@code effectiveCostPrice}. The stored figure is a cache of this same
   * arithmetic, so the two agree on every record written through pricing -- but a cache that wins
   * over its own inputs is silently wrong everywhere at once when the two ever part, and a landed
   * cost is read by margin, stock valuation and COGS alike. Deriving makes the inputs the answer
   * and the cache the fallback, which is the way round that cannot go stale.
   *
   * <p>The fallback still matters: a record with no cost price has nothing to derive from, and one
   * written before this field existed has nothing stored. Both are answered correctly here.
   */
  public BigDecimal resolveEffectiveCostPrice() {
    BigDecimal derived = PricingUtils.computeEffectiveCostPrice(
        costPrice,
        purchaseAdditionalDiscount,
        purchaseScheme == null
            ? null
            : new com.inventory.pricing.domain.model.Scheme(
                purchaseScheme.getSchemeType(),
                purchaseScheme.getSchemePayFor(),
                purchaseScheme.getSchemeFree(),
                purchaseScheme.getSchemePercentage()));
    return derived != null ? derived : effectiveCostPrice;
  }
}
