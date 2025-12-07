package com.inventory.product.rest.dto.product;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductResponse {
  String id;
  String name;
  BigDecimal price;
  String companyCode;
  String productTypeCode;
}

