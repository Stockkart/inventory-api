package com.inventory.pricing.rest.dto.request;

import com.inventory.pricing.domain.model.Rate;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class CreatePricingRequest {
  private String shopId;
  private BigDecimal maximumRetailPrice;
  private BigDecimal costPrice;
  private BigDecimal priceToRetail;
  private List<Rate> rates;
  private String defaultRate;
  private BigDecimal additionalDiscount;
  private String sgst;
  private String cgst;
}
