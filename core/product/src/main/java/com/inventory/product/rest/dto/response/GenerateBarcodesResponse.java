package com.inventory.product.rest.dto.response;

import com.inventory.product.domain.model.enums.BarcodePoolStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GenerateBarcodesResponse {
  private List<BarcodePoolItemDto> items;

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class BarcodePoolItemDto {
    private String id;
    private String code;
    private BarcodePoolStatus status;
    private String productId;
    private String batchId;
    private String labelName;
    private String labelCompany;
    private BigDecimal labelPrice;
    private Instant createdAt;
  }
}
