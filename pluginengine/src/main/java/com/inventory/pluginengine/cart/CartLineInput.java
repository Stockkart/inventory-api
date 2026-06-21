package com.inventory.pluginengine.cart;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Data;

/** Neutral cart line from API — resolved via {@code sellableRef}. */
@Data
@Builder
public class CartLineInput {

  /** Encoded ref, e.g. {@code inventory:lotId} or {@code menu:itemId}. */
  private String sellableRef;

  private Integer quantity;
  private Integer baseQuantity;
  private String unit;
  private BigDecimal priceToRetail;
  private BigDecimal saleAdditionalDiscount;
  private String schemeType;
  private Integer schemePayFor;
  private Integer schemeFree;
  private BigDecimal schemePercentage;
}
