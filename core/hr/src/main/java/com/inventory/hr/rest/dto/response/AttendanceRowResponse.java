package com.inventory.hr.rest.dto.response;

import com.inventory.hr.domain.model.enums.AttendanceSource;
import com.inventory.hr.domain.model.enums.DayStatus;
import com.inventory.hr.domain.model.enums.PunchFlag;
import com.inventory.hr.domain.model.enums.PunchSource;
import com.inventory.hr.service.DayStatusResolver;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One resolved employee-day (daily register, save response). */
@Data
@NoArgsConstructor
public class AttendanceRowResponse {
  private String employeeId;
  private String code;
  private String name;
  /** null = not given. */
  private String designation;
  private String date;
  private DayStatus status;
  private DayStatusResolver.Source source;
  /** true = half-day leave; {@code otherHalf} says what the rest of the day is. */
  private boolean halfDayLeave;
  private DayStatus otherHalf;
  /** Approved leave shown on this day; null = none. */
  private String leaveRequestId;
  private boolean late;
  private String inTime;
  private String outTime;
  private Integer workedMinutes;
  private int overtimeMinutes;
  private boolean autoHalfDay;
  private String note;
  /** Who stored the mark and when; null = derived day. */
  private Instant markedAt;
  private String markedByUserId;
  /** The day's month is locked by payroll. */
  private boolean locked;
  /**
   * Where the stored mark shown came from (MANUAL, BULK_PRESENT, SELF_CHECK_IN or BIOMETRIC_APP;
   * a mark stored without one is MANUAL); null = no stored mark shown. Not the same as {@code source}, which
   * says what decided the status.
   */
  private AttendanceSource markSource;
  /** Self check-in day with one punch that is over (R4.4). */
  private boolean missingCheckOut;
  /**
   * Flags of the day's counted punches (OUTSIDE_SHOP, LOW_ACCURACY, NO_LOCATION, FAKE_LOCATION,
   * RECORDED_OFFLINE); a flag only a duplicate punch carries is left out (R8.4, R8.7). Empty =
   * none.
   */
  private List<PunchFlag> flags = new ArrayList<>();
  /**
   * Source of the day's earliest counted punch (SELF or BIOMETRIC_APP), read from the day's
   * punches; null when the day has no counted punch. Daily register only.
   */
  private PunchSource punchSource;
  /**
   * A hand-made mark, change or clear of this day must carry a reason (R7.2): Biometric app
   * check-in is on for the shop and the employee has an active paired phone.
   */
  private boolean markReasonRequired;
  /**
   * "No punch yet" hint (R7.7–R7.8): the row is today's, Biometric app check-in is on, the
   * employee has an active paired phone, and the day has neither a punch nor a stored mark.
   */
  private boolean noPunchYet;
}
