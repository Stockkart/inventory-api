package com.inventory.product.rest.dto.product;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

@Value
@Builder
public class ProductResponse {
  String id;
  String name;
  BigDecimal price;
  String companyCode;
  String productTypeCode;
}

