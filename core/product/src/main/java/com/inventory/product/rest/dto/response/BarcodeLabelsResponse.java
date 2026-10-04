package com.inventory.product.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Response of {@code POST /barcodes/labels}: one {@link BarcodeLabelDto} per (code, product) pair
 * plus the effective layout the values were resolved against (Req 6.12).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BarcodeLabelsResponse {
  private List<BarcodeLabelDto> labels;
  private LabelLayoutResponse layout;

  public BarcodeLabelsResponse(List<BarcodeLabelDto> labels) {
    this(labels, null);
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class BarcodeLabelDto {
    private String code;
    private String name;
    private String companyName;
    private BigDecimal price;
    private String productId;
    /** fieldKey → formatted value, one entry per enabled field of {@code layout} (Req 6.1). */
    private Map<String, String> values;

    public BarcodeLabelDto(
        String code, String name, String companyName, BigDecimal price, String productId) {
      this(code, name, companyName, price, productId, null);
    }
  }
}
