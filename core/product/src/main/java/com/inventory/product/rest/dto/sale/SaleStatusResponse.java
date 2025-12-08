package com.inventory.product.rest.dto.sale;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SaleStatusResponse {
  String saleId;
  boolean valid;
}

