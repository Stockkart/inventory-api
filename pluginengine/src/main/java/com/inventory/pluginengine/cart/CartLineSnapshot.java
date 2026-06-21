package com.inventory.pluginengine.cart;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Data;

/** Vertical-neutral cart line output — mapped to purchase line items in core product. */
@Data
@Builder
public class CartLineSnapshot {

  private String sellableRef;
  private String stockRef;
  private CartSellMode sellMode;

  private String name;
  private String billingMode;
  private BigDecimal quantity;
  private String saleUnit;
  private String baseUnit;
  private String packUnitUqc;
  private Integer baseQuantity;
  private Integer unitFactor;

  private BigDecimal maximumRetailPrice;
  private BigDecimal priceToRetail;
  private BigDecimal discount;
  private BigDecimal saleAdditionalDiscount;
  private BigDecimal totalAmount;
  private String sgst;
  private String cgst;
  private BigDecimal costPrice;
  private BigDecimal costTotal;

  private String schemeType;
  private Integer schemePayFor;
  private Integer schemeFree;
  private BigDecimal schemePercentage;
}
