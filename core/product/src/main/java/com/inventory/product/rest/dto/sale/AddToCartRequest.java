package com.inventory.product.rest.dto.sale;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class AddToCartRequest {

  private String businessType;
  private List<CartItem> items;
  // Customer info for finding/creating customer (optional)
  private String customerName;
  private String customerAddress;
  private String customerPhone;
  private String customerEmail;
  // Or provide customerId directly (optional)
  private String customerId;

  @Data
  public static class CartItem {
    private String id;
    private Integer quantity;
    private BigDecimal sellingPrice;
  }
}

