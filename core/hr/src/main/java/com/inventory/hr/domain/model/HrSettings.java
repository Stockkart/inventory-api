package com.inventory.hr.domain.model;

import com.inventory.hr.domain.model.enums.LeaveYearStartMonth;
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
  /** Optimistic lock; a lost update returns 409 HR_CONCURRENT_UPDATE. null = never saved. */
  @Version private Long revision;
  private Instant createdAt;
  private Instant updatedAt;
  private String updatedByUserId;

  /** Weekly-off days that apply from {@code effectiveFrom} (YYYY-MM) on. */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class WeeklyOffEntry {
    private String effectiveFrom;
    private List<DayOfWeek> days = new ArrayList<>();
  }
}
