package com.inventory.hr.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.HrSettings;
import com.inventory.hr.rest.dto.request.UpdateHrSettingsRequest;
import com.inventory.hr.utils.HrDateUtils;
import com.inventory.hr.utils.PayCycle;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.util.HashSet;
import org.springframework.stereotype.Component;

@Component
public class HrSettingsValidator {

  static final int MAX_GRACE_MINUTES = 240;
  static final BigDecimal MAX_MULTIPLIER = new BigDecimal("5");
  public static final int MIN_RADIUS_METRES = 30;
  public static final int MAX_RADIUS_METRES = 1_000;

  /** Field formats and ranges; null fields are not checked (they keep the current value). */
  public void validate(UpdateHrSettingsRequest request) {
    if (request == null) {
      throw new ValidationException("Request cannot be null");
    }
    if (request.getWeeklyOff() != null) {
      validateWeeklyOff(request.getWeeklyOff());
    }
    if (request.getWorkStart() != null && !HrDateUtils.isTime(request.getWorkStart())) {
      throw new ValidationException("Work start must be a time like 09:00");
    }
    if (request.getWorkEnd() != null && !HrDateUtils.isTime(request.getWorkEnd())) {
      throw new ValidationException("Work end must be a time like 21:00");
    }
    Integer grace = request.getGraceMinutes();
    if (grace != null && (grace < 0 || grace > MAX_GRACE_MINUTES)) {
      throw new ValidationException("Grace minutes must be between 0 and " + MAX_GRACE_MINUTES);
    }
    BigDecimal halfDay = request.getHalfDayBelowHours();
    if (halfDay != null
        && (halfDay.signum() <= 0 || halfDay.compareTo(new BigDecimal("24")) >= 0)) {
      throw new ValidationException("Half-day hours must be more than 0 and less than 24");
    }
    BigDecimal multiplier = request.getOvertimeMultiplier();
    if (multiplier != null
        && (multiplier.compareTo(BigDecimal.ONE) < 0 || multiplier.compareTo(MAX_MULTIPLIER) > 0)) {
      throw new ValidationException("Overtime multiplier must be between 1 and 5");
    }
    Integer startDay = request.getPayCycleStartDay();
    if (startDay != null
        && (startDay < PayCycle.MIN_START_DAY || startDay > PayCycle.MAX_START_DAY)) {
      throw new ValidationException("The salary month must start on a day from "
          + PayCycle.MIN_START_DAY + " to " + PayCycle.MAX_START_DAY);
    }
    if (request.getAttendanceCapture() != null) {
      validateAttendanceCaptureChange(request.getAttendanceCapture());
    }
  }

  /** Ranges of the fields sent; null fields keep the stored value and are not checked. */
  static void validateAttendanceCaptureChange(UpdateHrSettingsRequest.AttendanceCaptureChange c) {
    // written as !(in range) so NaN fails too
    if (c.getShopLat() != null && !(c.getShopLat() >= -90 && c.getShopLat() <= 90)) {
      throw new ValidationException("Shop latitude must be between -90 and 90");
    }
    if (c.getShopLng() != null && !(c.getShopLng() >= -180 && c.getShopLng() <= 180)) {
      throw new ValidationException("Shop longitude must be between -180 and 180");
    }
    Integer radius = c.getRadiusMetres();
    if (radius != null && (radius < MIN_RADIUS_METRES || radius > MAX_RADIUS_METRES)) {
      throw new ValidationException("The shop radius must be between " + MIN_RADIUS_METRES
          + " and " + MAX_RADIUS_METRES + " metres");
    }
  }

  /**
   * Checked on the merged values (the coordinates may be stored already): the location check
   * needs the shop's location.
   */
  public void validateAttendanceCapture(HrSettings.AttendanceCapture merged) {
    if (Boolean.TRUE.equals(merged.getLocationCheck())
        && (merged.getShopLat() == null || merged.getShopLng() == null)) {
      throw new ValidationException(
          "Set the shop location (Use my current location, in the shop) before turning on the "
              + "location check");
    }
  }

  /** Start before end, checked on the merged values (one side may come from the stored row). */
  public void validateHours(String workStart, String workEnd) {
    if (HrDateUtils.parseTime(workStart) == null || HrDateUtils.parseTime(workEnd) == null
        || !HrDateUtils.parseTime(workStart).isBefore(HrDateUtils.parseTime(workEnd))) {
      throw new ValidationException("Work start must be before work end (no overnight hours)");
    }
  }

  private static void validateWeeklyOff(UpdateHrSettingsRequest.WeeklyOffChange change) {
    if (!HrDateUtils.isPeriod(change.getEffectiveFrom())) {
      throw new ValidationException("Weekly off needs the month it starts in, like 2025-04");
    }
    if (change.getDays() == null || change.getDays().isEmpty()) {
      throw new ValidationException("Choose at least one weekly off day");
    }
    // stream check: List.of(...).contains(null) would throw instead of answering
    if (change.getDays().stream().anyMatch(java.util.Objects::isNull)) {
      throw new ValidationException("Weekly off days must be days of the week");
    }
    if (new HashSet<DayOfWeek>(change.getDays()).size() != change.getDays().size()) {
      throw new ValidationException("Each weekly off day may appear only once");
    }
    if (change.getDays().size() >= DayOfWeek.values().length) {
      throw new ValidationException("At least one day of the week must be a working day");
    }
  }
}
