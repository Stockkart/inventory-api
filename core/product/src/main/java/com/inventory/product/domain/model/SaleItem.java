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
public class SaleItem {

  private String productId;
  private String productName;
  private Integer quantity;
  private BigDecimal salePrice;
  private BigDecimal discount;
  private BigDecimal total;
}

