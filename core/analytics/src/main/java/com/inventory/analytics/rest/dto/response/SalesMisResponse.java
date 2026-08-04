package com.inventory.analytics.rest.dto.response;

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
public class SalesMisResponse {
  private LocalDate from;
  private LocalDate to;

  /** Day-wise trading summary, oldest first; one entry per day that had sales activity. */
  @Builder.Default private List<SalesMisDailyRowDto> dailyRows = new ArrayList<>();

  @Builder.Default private List<SalesMisRowDto> rows = new ArrayList<>();
  private SalesMisSummaryDto summary;
}
