package com.inventory.product.rest.dto.request;

import lombok.Data;

@Data
public class SettleCafeOrderRequest {

  /** Required by CheckoutValidator, same value the sell screen already sends on addToCart. */
  private String businessType;

  private String paymentMethod;
}
