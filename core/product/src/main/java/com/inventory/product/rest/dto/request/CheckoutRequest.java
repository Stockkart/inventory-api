package com.inventory.product.rest.dto.request;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class CheckoutRequest {

  private String businessType;
  private List<CheckoutItem> items;
  private String paymentMethod; // Defaults to "CASH" if not provided

  @Data
  public static class CheckoutItem {
    private String id;
    private Integer quantity;
    private BigDecimal priceToRetail;
  }
}

