package com.inventory.pricing.rest.dto.response;

import com.inventory.pricing.service.api.InventoryPricingImpl;
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
  /** True when costPrice was entered off a bill whose rates include GST. */
  private Boolean costPriceIncludesTax;

  public boolean isEmpty() {
    return maximumRetailPrice == null && costPrice == null && priceToRetail == null
        && (rates == null || rates.isEmpty())
        && saleAdditionalDiscount == null && sgst == null && cgst == null;
  }

  /** The price the lot sells at; see {@link com.inventory.pricing.api.LotPricing#effectivePrice}. */
  public BigDecimal getEffectivePrice() {
    return PricingUtils.resolveEffectivePriceFromReadDto(this);
  }

  /** Landed cost per unit; see {@link com.inventory.pricing.api.LotPricing#resolveEffectiveCostPrice}. */
  public BigDecimal resolveEffectiveCostPrice() {
    // one implementation, in the api record other modules read
    return InventoryPricingImpl.toLotPricing(this).resolveEffectiveCostPrice();
  }
}
