package com.inventory.hr.rest.dto.response;

import com.inventory.hr.domain.model.enums.LeaveYearStartMonth;
import com.inventory.hr.domain.model.enums.OutsideAction;
import com.inventory.hr.domain.model.enums.PerDayBasis;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

/** HR settings with defaults filled in. {@code saved = false} means nothing is stored yet. */
@Data
@NoArgsConstructor
public class HrSettingsResponse {
  private boolean saved;
  private List<WeeklyOffEntryDto> weeklyOffSchedule;
  /** Weekly-off days that apply this month. */
  private List<DayOfWeek> currentWeeklyOffDays;
  /** Salary month (PayCycle, YYYY-MM) containing today; computed, read-only. */
  private String currentSalaryPeriod;
  private String workStart;
  private String workEnd;
  private Integer graceMinutes;
  private BigDecimal halfDayBelowHours;
  private Boolean overtimeEnabled;
  private BigDecimal overtimeMultiplier;
  private PerDayBasis perDayBasis;
  private LeaveYearStartMonth leaveYearStartMonth;
  /** Day 1–28 the salary month starts on (default 1 = calendar month). */
  private Integer payCycleStartDay;
  /** Self check-in and location check, defaults filled in. */
  private AttendanceCaptureDto attendanceCapture;
  /** Send back on PUT for lost-update protection; null = never saved. */
  private Long revision;
  private Instant updatedAt;

  @Data
  @NoArgsConstructor
  public static class AttendanceCaptureDto {
    private boolean selfCheckIn;
    private boolean locationCheck;
    /** null = shop location not set yet. */
    private Double shopLat;
    private Double shopLng;
    private int radiusMetres;
    private OutsideAction outsideAction;
  }

  @Data
  @NoArgsConstructor
  public static class WeeklyOffEntryDto {
    private String effectiveFrom;
    private List<DayOfWeek> days;
  }
}
