package com.inventory.product.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceSeriesResponse {
  private String shopId;
  private String prefix;
  private int padLength;
  private String source;
  private String currentFy;
  private String nextPreview;
  private boolean locked;
  private Long lastCounter;
}
