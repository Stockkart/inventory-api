package com.inventory.hr.utils;

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
}
