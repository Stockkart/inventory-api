package com.inventory.product.rest.dto.inventory;

import com.inventory.product.domain.model.Rate;
import lombok.*;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InventoryPricingDto {

  private String id;
  private BigDecimal maximumRetailPrice;
  private BigDecimal costPrice;
  private BigDecimal sellingPrice;
  private List<Rate> rates;
  private String setPrice;
}
