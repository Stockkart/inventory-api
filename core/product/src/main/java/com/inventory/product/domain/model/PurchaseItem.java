package com.inventory.product.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseItem {

  private String inventoryId;
  private String name;
  private Integer quantity;
  private BigDecimal maximumRetailPrice;
  private BigDecimal sellingPrice;
  private BigDecimal discount;
}

