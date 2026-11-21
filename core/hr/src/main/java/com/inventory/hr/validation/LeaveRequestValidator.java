package com.inventory.hr.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.hr.utils.HrDateUtils;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Leave request input (R6.5, O9): strict dates, from ≤ to, at most 90 calendar days, half day only
 * for a single date, short reason and note.
 */
@Component
public class LeaveRequestValidator {

  /** Longest request in calendar days, both ends included (O9; bounds the overlay query). */
  public static final int MAX_DAYS = 90;
  public static final int MAX_TEXT_LENGTH = 500;

  /** The validated range; {@code halfDay} null counts as false. */
  public record Range(LocalDate from, LocalDate to, boolean halfDay) {}

  public Range validate(String leaveTypeId, String fromDate, String toDate, Boolean halfDay,
      String reason) {
    if (!StringUtils.hasText(leaveTypeId)) {
      throw new ValidationException("leaveTypeId is required");
    }
    LocalDate from = requireDate("fromDate", fromDate);
    LocalDate to = requireDate("toDate", toDate);
    if (to.isBefore(from)) {
      throw new ValidationException("The leave must end on or after its first day");
    }
    if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
      throw new ValidationException("A leave request may cover at most " + MAX_DAYS + " days");
    }
    boolean half = Boolean.TRUE.equals(halfDay);
    if (half && !from.equals(to)) {
      throw new ValidationException("A half day leave must start and end on the same date");
    }
    validateText("reason", reason);
    return new Range(from, to, half);
  }

  public void requireEmployeeId(String employeeId) {
    if (!StringUtils.hasText(employeeId)) {
      throw new ValidationException("employeeId is required");
    }
  }

  public void validateText(String field, String value) {
    if (value != null && value.length() > MAX_TEXT_LENGTH) {
      throw new ValidationException(field + " may have at most " + MAX_TEXT_LENGTH
          + " characters");
    }
  }

  private static LocalDate requireDate(String field, String value) {
    LocalDate date = HrDateUtils.parseDate(value);
    if (date == null) {
      throw new ValidationException(field + " must be a date like 2025-04-30");
    }
    return date;
  }
}
