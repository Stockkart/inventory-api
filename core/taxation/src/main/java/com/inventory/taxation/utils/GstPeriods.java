package com.inventory.taxation.utils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Dates of the GST returns, in India time (Asia/Kolkata).
 *
 * <p>A return period is a calendar month in India and a document's date is its Indian date, whatever
 * zone the server runs in. These used the JVM's default zone; a container on UTC starts October at
 * 05:30 IST on 1 October, so a sale billed between midnight and 05:30 on the first of a month was
 * filed in the month before, dated the day before.
 */
public final class GstPeriods {

  public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

  private GstPeriods() {}

  /** First instant of the month (inclusive start of the period). */
  public static Instant monthStart(int year, int month) {
    return LocalDate.of(year, month, 1).atStartOfDay(ZONE).toInstant();
  }

  /** First instant of the next month (exclusive end of the period). */
  public static Instant monthEnd(int year, int month) {
    return LocalDate.of(year, month, 1).plusMonths(1).atStartOfDay(ZONE).toInstant();
  }

  /** The Indian date of an instant; today in India when it is null. */
  public static LocalDate dateOf(Instant instant) {
    return instant != null ? LocalDate.ofInstant(instant, ZONE) : LocalDate.now(ZONE);
  }
}
