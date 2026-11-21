package com.inventory.hr.rest.dto.request;

import com.inventory.hr.domain.model.enums.LeaveYearStartMonth;
import com.inventory.hr.domain.model.enums.OutsideAction;
import com.inventory.hr.domain.model.enums.PerDayBasis;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.util.List;
import lombok.Data;

/** HR settings change. A null field keeps its current value (or default). */
@Data
public class UpdateHrSettingsRequest {
  /** New weekly-off days and the month they start in; null = weekly off unchanged. */
  private WeeklyOffChange weeklyOff;
  /** HH:mm. */
  private String workStart;
  /** HH:mm. */
  private String workEnd;
  private Integer graceMinutes;
  private BigDecimal halfDayBelowHours;
  private Boolean overtimeEnabled;
  private BigDecimal overtimeMultiplier;
  private PerDayBasis perDayBasis;
  private LeaveYearStartMonth leaveYearStartMonth;
  /**
   * Day 1–28 the salary month starts on; null = unchanged. Refused (409 HR_RUN_STATE_CONFLICT)
   * once any payroll run is FINALISING, FINALISED or REOPENING.
   */
  private Integer payCycleStartDay;
  /** Self check-in and location check; null = unchanged, and so is every null field inside. */
  private AttendanceCaptureChange attendanceCapture;
  /**
   * Revision the client last read; required once settings are saved (stale = 409), null on the
   * first save.
   */
  private Long revision;

  /** Partial change of {@code attendanceCapture}: a null field keeps its current value. */
  @Data
  public static class AttendanceCaptureChange {
    private Boolean selfCheckIn;
    private Boolean locationCheck;
    /** The shop's own location ("Use my current location" in the shop), −90..90. */
    private Double shopLat;
    /** −180..180. */
    private Double shopLng;
    /** 30–1,000. */
    private Integer radiusMetres;
    private OutsideAction outsideAction;
  }

  @Data
  public static class WeeklyOffChange {
    /** YYYY-MM the new days apply from. */
    private String effectiveFrom;
    private List<DayOfWeek> days;
  }
}
