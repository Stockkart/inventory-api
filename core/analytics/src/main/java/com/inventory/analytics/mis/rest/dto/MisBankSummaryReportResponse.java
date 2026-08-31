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
public class MisBankSummaryReportResponse {

  private LocalDate from;
  private LocalDate to;

  /**
   * Where opening came from. {@code SNAPSHOT} means a closed prior period was carried
   * forward and the number is frozen; {@code DERIVED} means it was reconstructed by
   * rolling live stock counters backwards, and will move if historical data is edited.
   */
  private String openingSource;

  /** Period end of the snapshot that fed opening, when {@code openingSource} is SNAPSHOT. */
  private LocalDate openingSnapshotDate;

  /** True when any row has a non-zero adjustment, so the UI knows to show that column. */
  private boolean hasAdjustments;

  /** True once this period itself has been closed; a re-close then needs force. */
  private boolean periodClosed;

  private MisBankSummaryTotalsDto totals;

  @Builder.Default private List<MisBankSummaryRowDto> rows = new ArrayList<>();

  private int page;
  private int size;
  private long totalItems;
}
