package com.inventory.analytics.utils;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * Time buckets of the analytics charts, in India time (Asia/Kolkata). Keys are unchanged in shape:
 * hour {@code 2026-10-01T05:00}, day {@code 2026-10-01}, week {@code 2026-09-28_week} (its Monday),
 * month {@code 2026-10}; anything else is treated as day.
 *
 * <p>MongoDB buckets by IST day (or hour, for hourly charts) via {@link #MONGO_DAY_FORMAT} /
 * {@link #MONGO_HOUR_FORMAT}; {@link #keyOf} turns that into the requested granularity's key.
 */
public final class AnalyticsPeriods {

  public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
  static final String MONGO_DAY_FORMAT = "%Y-%m-%d";
  static final String MONGO_HOUR_FORMAT = "%Y-%m-%dT%H:00";

  private AnalyticsPeriods() {}

  /** True when the chart needs MongoDB to bucket by hour rather than by day. */
  public static boolean hourly(String granularity) {
    return granularity != null && "hour".equalsIgnoreCase(granularity);
  }

  /**
   * The chart key for a MongoDB bucket ({@code yyyy-MM-dd}, or {@code yyyy-MM-ddTHH:00} when
   * {@link #hourly}).
   */
  public static String keyOf(String mongoBucket, String granularity) {
    switch (granularity.toLowerCase()) {
      case "hour":
        return LocalDateTime.parse(mongoBucket).truncatedTo(ChronoUnit.HOURS).toString();
      case "week":
        return LocalDate.parse(mongoBucket).with(DayOfWeek.MONDAY) + "_week";
      case "month":
        return mongoBucket.substring(0, 7);
      case "day":
      default:
        return mongoBucket;
    }
  }

  /** Start of the bucket a key names (IST); "now" for a key that does not parse, as before. */
  public static Instant start(String key, String granularity) {
    try {
      switch (granularity.toLowerCase()) {
        case "hour":
          return LocalDateTime.parse(key).atZone(ZONE).toInstant();
        case "week":
          return LocalDate.parse(key.replace("_week", "")).atStartOfDay(ZONE).toInstant();
        case "month":
          String[] parts = key.split("-");
          return LocalDate.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), 1)
              .atStartOfDay(ZONE)
              .toInstant();
        case "day":
        default:
          return LocalDate.parse(key).atStartOfDay(ZONE).toInstant();
      }
    } catch (RuntimeException e) {
      return Instant.now();
    }
  }

  /** End (exclusive) of the bucket starting at {@code start}; a month ends at the next month's start. */
  public static Instant end(Instant start, String granularity) {
    switch (granularity.toLowerCase()) {
      case "hour":
        return start.plus(1, ChronoUnit.HOURS);
      case "week":
        return start.plus(7, ChronoUnit.DAYS);
      case "month":
        return start.atZone(ZONE).plusMonths(1).toInstant();
      case "day":
      default:
        return start.plus(1, ChronoUnit.DAYS);
    }
  }
}
