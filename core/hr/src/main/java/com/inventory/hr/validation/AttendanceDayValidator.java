package com.inventory.hr.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.enums.AttendanceStatus;
import com.inventory.hr.rest.dto.request.MarkUnmarkedPresentRequest;
import com.inventory.hr.rest.dto.request.SaveAttendanceDaysRequest;
import com.inventory.hr.utils.HrDateUtils;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Attendance input (design "Memory" caps): at most 500 rows per save and 31 dates of one month
 * per mark-unmarked call, strict dates and times, in-time before out-time, no future dates
 * ("today" is Asia/Kolkata).
 */
@Component
public class AttendanceDayValidator {

  public static final int MAX_ROWS = 500;
  public static final int MAX_DATES = 31;
  public static final int MAX_NOTE_LENGTH = 200;
  /** Request value that clears a stored mark. */
  public static final String UNMARKED = "UNMARKED";

  /** Checks a save; returns nothing, throws {@link ValidationException} on the first problem. */
  public void validateSave(SaveAttendanceDaysRequest request, LocalDate today) {
    if (request == null || request.getRows() == null || request.getRows().isEmpty()) {
      throw new ValidationException("Send at least one attendance row");
    }
    if (request.getRows().size() > MAX_ROWS) {
      throw new ValidationException("Send at most " + MAX_ROWS + " attendance rows at a time");
    }
    Set<String> keys = new HashSet<>();
    for (SaveAttendanceDaysRequest.Row row : request.getRows()) {
      if (row == null || !StringUtils.hasText(row.getEmployeeId())) {
        throw new ValidationException("employeeId is required on every row");
      }
      LocalDate date = requireDate("date", row.getDate());
      requireNotFuture(date, today);
      if (!keys.add(row.getEmployeeId() + "|" + row.getDate())) {
        throw new ValidationException("Each employee and date may appear only once");
      }
      parseStatus(row.getStatus());
      LocalTime in = optionalTime("inTime", row.getInTime());
      LocalTime out = optionalTime("outTime", row.getOutTime());
      if (in != null && out != null && !out.isAfter(in)) {
        throw new ValidationException("Out-time must be after in-time on the same day");
      }
      if (row.getNote() != null && row.getNote().length() > MAX_NOTE_LENGTH) {
        throw new ValidationException("A note may have at most " + MAX_NOTE_LENGTH
            + " characters");
      }
    }
  }

  /**
   * The stored status for a row value; null for {@link #UNMARKED} (clear the mark). LEAVE is
   * refused: leave days come only from leave requests (D4).
   */
  public AttendanceStatus parseStatus(String status) {
    if (!StringUtils.hasText(status)) {
      throw new ValidationException("status is required on every row");
    }
    String s = status.trim().toUpperCase();
    if (UNMARKED.equals(s)) {
      return null;
    }
    if ("LEAVE".equals(s)) {
      throw new ValidationException("Use 'Record leave' to mark a leave day");
    }
    try {
      return AttendanceStatus.valueOf(s);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Unknown attendance status: " + status);
    }
  }

  /** Sorted distinct dates of one month, none in the future. */
  public List<LocalDate> validateMarkUnmarked(MarkUnmarkedPresentRequest request,
      LocalDate today) {
    if (request == null || request.getDates() == null || request.getDates().isEmpty()) {
      throw new ValidationException("Send at least one date");
    }
    TreeSet<LocalDate> dates = new TreeSet<>();
    for (String value : request.getDates()) {
      LocalDate date = requireDate("dates", value);
      requireNotFuture(date, today);
      dates.add(date);
    }
    if (dates.size() > MAX_DATES) {
      throw new ValidationException("Send at most " + MAX_DATES + " dates at a time");
    }
    if (!YearMonth.from(dates.first()).equals(YearMonth.from(dates.last()))) {
      throw new ValidationException("All dates must be in the same month");
    }
    if (request.getEmployeeIds() != null) {
      for (String id : request.getEmployeeIds()) {
        if (!StringUtils.hasText(id)) {
          throw new ValidationException("employeeIds may not contain blanks");
        }
      }
      if (request.getEmployeeIds().size() > MAX_ROWS) {
        throw new ValidationException("Send at most " + MAX_ROWS + " employees at a time");
      }
    }
    return List.copyOf(dates);
  }

  public LocalDate requireDate(String field, String value) {
    LocalDate date = HrDateUtils.parseDate(value);
    if (date == null) {
      throw new ValidationException(field + " must be a date like 2025-04-30");
    }
    return date;
  }

  public YearMonth requirePeriod(String value) {
    YearMonth period = HrDateUtils.parsePeriod(value);
    if (period == null) {
      throw new ValidationException("period must be a month like 2025-04");
    }
    return period;
  }

  private static void requireNotFuture(LocalDate date, LocalDate today) {
    if (date.isAfter(today)) {
      throw new ValidationException("Attendance cannot be marked for a future date");
    }
  }

  private static LocalTime optionalTime(String field, String value) {
    if (value == null || value.isEmpty()) {
      return null;
    }
    LocalTime time = HrDateUtils.parseTime(value);
    if (time == null) {
      throw new ValidationException(field + " must be a time like 09:30");
    }
    return time;
  }
}
