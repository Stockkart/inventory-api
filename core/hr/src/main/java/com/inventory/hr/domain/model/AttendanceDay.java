package com.inventory.hr.domain.model;

import com.inventory.hr.domain.model.enums.AttendanceSource;
import com.inventory.hr.domain.model.enums.AttendanceStatus;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One marked employee-day; unique {@code (shopId, employeeId, date)}. Only marked days are stored:
 * unmarked days, derived week-offs and holidays, and approved leave are worked out on read (D3).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "hr_attendance_days")
public class AttendanceDay {

  @Id private String id;
  private String shopId;
  private String employeeId;
  /** yyyy-MM-dd. */
  private String date;
  /**
   * Calendar YYYY-MM of {@code date}; informational only (nothing queries it). It is NOT the
   * salary month: lock checks map {@code date} through {@code PayCycle}.
   */
  private String period;
  private AttendanceStatus status;
  /** HH:mm; null = not entered. */
  private String inTime;
  /** HH:mm; null = not entered. */
  private String outTime;
  /** null = no times entered. */
  private Integer workedMinutes;
  /** true = PRESENT turned HALF_DAY because worked hours were below the half-day rule; null = false. */
  private Boolean autoHalfDay;
  /** null = MANUAL. */
  private AttendanceSource source;
  /** null = none. */
  private String note;
  private Instant markedAt;
  private String markedByUserId;
}
