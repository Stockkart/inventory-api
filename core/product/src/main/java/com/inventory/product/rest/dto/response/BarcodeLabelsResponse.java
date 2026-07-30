package com.inventory.product.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BarcodeLabelsResponse {
  private List<BarcodeLabelDto> labels;

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class BarcodeLabelDto {
    private String code;
    private String name;
    private String companyName;
    private BigDecimal price;
    private String productId;
  }
}
