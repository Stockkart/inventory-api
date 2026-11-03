package com.inventory.hr.rest.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.inventory.hr.domain.model.enums.AttendanceSource;
import com.inventory.hr.domain.model.enums.DayStatus;
import com.inventory.hr.domain.model.enums.PunchFlag;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code GET /attendance/month}: employees × days of one month with totals (R5.11, R5.12). Cells
 * are kept small (up to 9,300 per response): flags that are false are left out.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MonthRegisterResponse {
  private String period;
  private boolean locked;
  /** Every date of the month, yyyy-MM-dd; cells are in the same order. */
  private List<String> dates = new ArrayList<>();
  private List<Row> rows = new ArrayList<>();

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Row {
    private String employeeId;
    private String code;
    private String name;
    private List<Cell> cells = new ArrayList<>();
    private Totals totals;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Cell {
    private DayStatus status;
    /** null = false. */
    private Boolean late;
    /** null = false. */
    private Boolean halfDayLeave;
    /** Half-day leave only. */
    private DayStatus otherHalf;
    /**
     * The date is after today (Asia/Kolkata); null = false. A future Unmarked day is not counted
     * in {@code totals.unmarked} and is shown blank.
     */
    private Boolean future;
    /**
     * Where the stored mark shown came from, when it is not a hand-made one (SELF_CHECK_IN,
     * BULK_PRESENT); null = no stored mark shown, or a MANUAL one.
     */
    private AttendanceSource markSource;
    /** Self check-in day with one punch that is over (R4.4); null = false. */
    private Boolean missingCheckOut;
    /** Flags of the day's punches; null = none. */
    private List<PunchFlag> flags;

    /** A cell for a day up to today. */
    public Cell(DayStatus status, Boolean late, Boolean halfDayLeave, DayStatus otherHalf) {
      this(status, late, halfDayLeave, otherHalf, null);
    }

    public Cell(DayStatus status, Boolean late, Boolean halfDayLeave, DayStatus otherHalf,
        Boolean future) {
      this(status, late, halfDayLeave, otherHalf, future, null, null, null);
    }
  }

  /** Day counts; halves come from half-day leave. NOT_EMPLOYED days are not counted. */
  @Data
  @NoArgsConstructor
  public static class Totals {
    private BigDecimal present = BigDecimal.ZERO;
    private int halfDays;
    private BigDecimal absent = BigDecimal.ZERO;
    private BigDecimal leave = BigDecimal.ZERO;
    private BigDecimal weekOff = BigDecimal.ZERO;
    private BigDecimal holiday = BigDecimal.ZERO;
    private BigDecimal unmarked = BigDecimal.ZERO;
    private int lateDays;
    private int workedMinutes;
    private int overtimeMinutes;
  }
}
