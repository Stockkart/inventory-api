package com.inventory.product.rest.dto.sale;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class AddToCartRequest {

  private String businessType;
  private List<CartItem> items;

  @Data
  public static class CartItem {
    private String lotId;
    private Integer quantity;
    private BigDecimal sellingPrice;
  }
}

