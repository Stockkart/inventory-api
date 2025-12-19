package com.inventory.product.rest.dto.sale;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class AddToCartRequest {

  private String businessType;
  private List<CartItem> items;
  private String customerName;      // Optional
  private String customerAddress;   // Optional
  private String customerPhone;     // Optional

  @Data
  public static class CartItem {
    private String id;
    private Integer quantity;
    private BigDecimal sellingPrice;
  }
}

