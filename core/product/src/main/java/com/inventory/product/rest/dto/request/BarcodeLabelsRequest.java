package com.inventory.product.rest.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BarcodeLabelsRequest {
  private List<String> productIds;
  private List<String> codes;
}
