package com.inventory.hr.rest.dto.response;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Advance statement of one employee (R7.5, R12.3): advances given and amounts recovered by
 * finalised payroll, oldest first, with the running balance still owed.
 */
@Data
@NoArgsConstructor
public class AdvanceStatementResponse {
  private String employeeId;
  private String employeeCode;
  private String employeeName;
  private List<Row> rows = new ArrayList<>();
  /** Balance after the last row. */
  private BigDecimal outstanding;

  /** GIVEN rows add to the balance, RECOVERED rows take from it; a cancelled advance adds 0. */
  public enum Kind {
    GIVEN,
    RECOVERED
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Row {
    /** yyyy-MM-dd; for RECOVERED the last day of the payroll period. */
    private String date;
    private Kind kind;
    private String advanceId;
    /** YYYY-MM of the payroll run for RECOVERED, null for GIVEN. */
    private String period;
    private BigDecimal amount;
    private BigDecimal balance;
    /** The advance was cancelled (never recovered), so it does not count. */
    private boolean cancelled;
  }
}
