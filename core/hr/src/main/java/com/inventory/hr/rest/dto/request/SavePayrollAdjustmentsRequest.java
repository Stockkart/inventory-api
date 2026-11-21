package com.inventory.hr.rest.dto.request;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code PUT /payroll-runs/{runId}/lines/{lineId}/adjustments} (R7.4, R8.9): replaces the line's
 * manual items and this month's advance deduction. The result must leave net pay ≥ 0.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SavePayrollAdjustmentsRequest {
  /** Replaces all manual items; empty or null = none. */
  private List<Item> manualItems = new ArrayList<>();
  /** null = automatic FIFO recovery. */
  private BigDecimal advanceOverride;

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Item {
    /** BONUS or OTHER_DEDUCTION. */
    private String kind;
    private String note;
    private BigDecimal amount;
  }
}
