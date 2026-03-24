package com.inventory.pricing.rest.dto.request;

import com.inventory.pricing.domain.model.Scheme;
import com.inventory.pricing.rest.dto.response.RateDto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PricingCreateCommand {
  private String shopId;
  private BigDecimal maximumRetailPrice;
  private BigDecimal costPrice;
  private BigDecimal priceToRetail;
  private List<RateDto> rates;
  private String defaultRate;
  private BigDecimal saleAdditionalDiscount;
  private BigDecimal purchaseAdditionalDiscount;
  private Scheme purchaseScheme;
  private Scheme saleScheme;
  private String sgst;
  private String cgst;
}
