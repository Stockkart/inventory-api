package com.inventory.hr.utils;

import com.inventory.hr.domain.model.Employee;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

/**
 * HR stores dates as {@code yyyy-MM-dd}, times as {@code HH:mm} and periods as {@code YYYY-MM}
 * strings (design D12: {@code MongoConfig} has no {@code LocalDate} converter). Parsing is strict,
 * so {@code 2025-02-30} or {@code 24:00} are rejected. "Today" is in the shop's zone, Asia/Kolkata.
 */
public final class HrDateUtils {

  public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT);
  private static final DateTimeFormatter PERIOD =
      DateTimeFormatter.ofPattern("uuuu-MM").withResolverStyle(ResolverStyle.STRICT);

  private HrDateUtils() {}

  /** The date, or null when {@code value} is not a strict {@code yyyy-MM-dd}. */
  public static LocalDate parseDate(String value) {
    if (value == null) {
      return null;
    }
    try {
      return LocalDate.parse(value, DATE);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  /** The time, or null when {@code value} is not a strict {@code HH:mm}. */
  public static LocalTime parseTime(String value) {
    if (value == null) {
      return null;
    }
    try {
      return LocalTime.parse(value, TIME);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  /** The month, or null when {@code value} is not a strict {@code YYYY-MM}. */
  public static YearMonth parsePeriod(String value) {
    if (value == null) {
      return null;
    }
    try {
      return YearMonth.parse(value, PERIOD);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  public static boolean isDate(String value) {
    return parseDate(value) != null;
  }

  public static boolean isTime(String value) {
    return parseTime(value) != null;
  }

  public static boolean isPeriod(String value) {
    return parsePeriod(value) != null;
  }

  /** {@code YYYY-MM} of a valid {@code yyyy-MM-dd} date. */
  public static String periodOf(String date) {
    return date.substring(0, 7);
  }

  public static LocalDate today() {
    return LocalDate.now(ZONE);
  }

  public static String currentPeriod() {
    return YearMonth.now(ZONE).format(PERIOD);
  }

  public static String format(LocalDate date) {
    return date.format(DATE);
  }

  /**
   * The date the employee counts as employed from: the join date, or for older rows saved without
   * one, the {@code createdAt} date in the shop's zone (never "start of time"). Null only when both
   * are missing. Queries feeding this must project {@code createdAt}.
   */
  public static LocalDate effectiveJoinDate(Employee employee) {
    LocalDate join = parseDate(employee.getJoinDate());
    if (join != null) {
      return join;
    }
    Instant created = employee.getCreatedAt();
    return created == null ? null : created.atZone(ZONE).toLocalDate();
  }

  /** {@link #effectiveJoinDate(Employee)} as {@code yyyy-MM-dd}, or null. */
  public static String effectiveJoinDateText(Employee employee) {
    LocalDate join = effectiveJoinDate(employee);
    return join == null ? null : format(join);
  }
}
