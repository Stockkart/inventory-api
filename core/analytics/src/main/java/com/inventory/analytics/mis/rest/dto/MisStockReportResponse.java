package com.inventory.analytics.mis.rest.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MisStockReportResponse {
  private MisStockSummaryDto summary;
  @Builder.Default private List<MisStockRowDto> rows = new ArrayList<>();
  private int page;
  private int size;
  private long totalItems;
}
