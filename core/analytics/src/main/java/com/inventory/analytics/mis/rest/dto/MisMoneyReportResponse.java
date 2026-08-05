package com.inventory.analytics.mis.rest.dto;

import java.time.LocalDate;
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
public class MisMoneyReportResponse {
  private LocalDate from;
  private LocalDate to;
  private MisMoneySummaryDto summary;
  @Builder.Default private List<MisMoneyRowDto> rows = new ArrayList<>();
  private int page;
  private int size;
  private long totalItems;
}
