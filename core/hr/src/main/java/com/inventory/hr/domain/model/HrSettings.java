package com.inventory.hr.domain.model;

import com.inventory.hr.domain.model.enums.LeaveYearStartMonth;
import com.inventory.hr.domain.model.enums.OutsideAction;
import com.inventory.hr.domain.model.enums.PerDayBasis;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * HR working rules of one shop (R2), unique {@code shopId}. Not stored until first saved; until
 * then {@code HrSettingsService} serves the defaults noted per field.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "hr_settings")
public class HrSettings {

  @Id private String id;
  private String shopId;
  /**
   * Weekly-off days over time; the latest entry with {@code effectiveFrom <= period} applies.
   * Empty or null = Sunday from the start of time.
   */
  private List<WeeklyOffEntry> weeklyOffSchedule = new ArrayList<>();
  /** HH:mm; null = 09:00. */
  private String workStart;
  /** HH:mm; null = 21:00. */
  private String workEnd;
  /** null = 15. */
  private Integer graceMinutes;
  /** null = 4 hours. */
  private BigDecimal halfDayBelowHours;
  /** null = false. */
  private Boolean overtimeEnabled;
  /** null = 1.5. */
  private BigDecimal overtimeMultiplier;
  /** null = CALENDAR_DAYS. */
  private PerDayBasis perDayBasis;
  /** null = JANUARY. */
  private LeaveYearStartMonth leaveYearStartMonth;
  /**
   * Day 1–28 the salary month starts on; null = 1 (calendar month). See {@code PayCycle}. Can be
   * changed only while no payroll run is FINALISING, FINALISED or REOPENING.
   */
  private Integer payCycleStartDay;
  /** How attendance is captured besides manual marks; null = everything off (defaults below). */
  private AttendanceCapture attendanceCapture;
  /** Optimistic lock; a lost update returns 409 HR_CONCURRENT_UPDATE. null = never saved. */
  @Version private Long revision;
  private Instant createdAt;
  private Instant updatedAt;
  private String updatedByUserId;

  /**
   * Self check-in and its location check (R1, R3). The shop's own coordinates are stored; an
   * employee's never are.
   */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class AttendanceCapture {
    /** null = false. */
    private Boolean selfCheckIn;
    /** null = false. Needs {@code shopLat}/{@code shopLng}. */
    private Boolean locationCheck;
    /** Degrees, −90..90; null = not set. */
    private Double shopLat;
    /** Degrees, −180..180; null = not set. */
    private Double shopLng;
    /** 30–1,000; null = 100. */
    private Integer radiusMetres;
    /** null = BLOCK. */
    private OutsideAction outsideAction;
  }

  /** Weekly-off days that apply from {@code effectiveFrom} (YYYY-MM) on. */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class WeeklyOffEntry {
    private String effectiveFrom;
    private List<DayOfWeek> days = new ArrayList<>();
  }
}
