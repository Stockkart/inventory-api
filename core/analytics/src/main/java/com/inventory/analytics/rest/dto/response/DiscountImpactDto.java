package com.inventory.analytics.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DiscountImpactDto {
  private BigDecimal totalDiscountGiven;
  private BigDecimal totalRevenueWithDiscount;
  private BigDecimal estimatedRevenueWithoutDiscount;
  private BigDecimal revenueLostToDiscount;
  private BigDecimal discountPercentOfRevenue;
  private Integer totalItemsWithDiscount;
  private Integer totalItemsSold;
  private BigDecimal averageDiscountPerItem;
}
