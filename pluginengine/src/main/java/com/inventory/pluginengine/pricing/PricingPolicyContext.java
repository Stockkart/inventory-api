package com.inventory.pluginengine.pricing;

import java.math.BigDecimal;
import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PricingPolicyContext {

  private String shopId;
  private String verticalId;
  private BigDecimal maximumRetailPrice;
  private BigDecimal costPrice;
  private BigDecimal priceToRetail;
  private BigDecimal sellingPrice;
  private List<PricingRateEntry> rates;
  private String defaultRate;
  private BigDecimal saleAdditionalDiscount;
  private BigDecimal purchaseAdditionalDiscount;
  private PricingSchemeEntry purchaseScheme;
  private PricingSchemeEntry saleScheme;
  private String sgst;
  private String cgst;
}
