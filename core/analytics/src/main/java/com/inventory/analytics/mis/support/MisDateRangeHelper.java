package com.inventory.analytics.mis.support;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Shop-local date range helpers for MIS reports. */
public final class MisDateRangeHelper {

  private MisDateRangeHelper() {}

  public static ZoneId zone() {
    return ZoneId.systemDefault();
  }

  public static LocalDate defaultFrom() {
    return LocalDate.now(zone()).withDayOfMonth(1);
  }

  public static LocalDate defaultTo() {
    return LocalDate.now(zone());
  }

  public static LocalDate resolveFrom(LocalDate from) {
    return from != null ? from : defaultFrom();
  }

  public static LocalDate resolveTo(LocalDate to) {
    return to != null ? to : defaultTo();
  }

  public static Instant startOfDay(LocalDate date) {
    return date.atStartOfDay(zone()).toInstant();
  }

  public static Instant endOfDay(LocalDate date) {
    return date.atTime(LocalTime.MAX).atZone(zone()).toInstant();
  }

  public static LocalDate toLocalDate(Instant instant) {
    if (instant == null) {
      return null;
    }
    return ZonedDateTime.ofInstant(instant, zone()).toLocalDate();
  }

  public static LocalDate businessDate(LocalDate txnDate, Instant createdAt) {
    if (txnDate != null) {
      return txnDate;
    }
    return toLocalDate(createdAt);
  }
}
