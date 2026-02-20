package com.inventory.pricing.rest.dto;

import com.inventory.pricing.domain.model.Rate;
import lombok.*;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatePricingRequest {

  private BigDecimal maximumRetailPrice;
  private BigDecimal costPrice;
  private BigDecimal sellingPrice;
  private List<Rate> rates;
  private String defaultPrice;
}
