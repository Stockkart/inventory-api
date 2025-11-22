package com.inventory.product.rest.dto.sale;

import lombok.Data;

import java.util.List;

@Data
public class CheckoutRequest {

  private String shopId;
  private String userId;
  private List<CheckoutItem> items;
  private String paymentMethod;

  @Data
  public static class CheckoutItem {
    private String barcode;
    private Integer qty;
    private Integer discount;
  }
}

