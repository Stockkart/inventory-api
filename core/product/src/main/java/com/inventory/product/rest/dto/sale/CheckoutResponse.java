package com.inventory.product.rest.dto.sale;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.math.BigDecimal;
import java.util.List;

@Value
@Builder
public class CheckoutResponse {
  String saleId;
  String invoiceNo;
  BigDecimal grandTotal;
  @Singular("item")
  List<SaleItemResponse> items;

  @Value
  @Builder
  public static class SaleItemResponse {
    String barcode;
    Integer qty;
    BigDecimal price;
  }
}

