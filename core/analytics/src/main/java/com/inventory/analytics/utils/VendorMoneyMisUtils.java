package com.inventory.analytics.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import org.springframework.util.StringUtils;

/**
 * Money, date and text helpers for the vendor money MIS.
 *
 * <p>These were private statics on the service with names like {@code nz} and {@code cap}; they are
 * pulled out here so the service reads as report logic rather than arithmetic plumbing.
 */
public final class VendorMoneyMisUtils {

  private VendorMoneyMisUtils() {}

  /** Reports are read by shop staff in local time; ledger dates must not drift across UTC midnight. */
  public static final ZoneId SHOP_ZONE = ZoneId.of("Asia/Kolkata");

  /** Rupee amounts are presented at 2dp. */
  private static final int MONEY_SCALE = 2;

  /** Characters kept from an id when abbreviating it for display. */
  private static final int SHORT_ID_LENGTH = 4;

  private static final String MISSING_ID = "----";

  /** {@code BigDecimal.ZERO} at reporting scale. */
  public static BigDecimal zeroMoney() {
    return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  /** The value, or zero when absent — for summing nullable amount columns. */
  public static BigDecimal zeroIfNull(BigDecimal value) {
    return value != null ? value : BigDecimal.ZERO;
  }

  /** Rounds to reporting scale, treating null as zero. */
  public static BigDecimal toMoneyScale(BigDecimal value) {
    return zeroIfNull(value).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  public static boolean isNonZero(BigDecimal value) {
    return zeroIfNull(value).signum() != 0;
  }

  /** Calendar date in the shop's timezone. */
  public static LocalDate toShopDate(Instant instant) {
    return instant == null ? null : instant.atZone(SHOP_ZONE).toLocalDate();
  }

  /** Start of {@code date} in the shop's timezone. */
  public static Instant startOfDay(LocalDate date) {
    return date.atStartOfDay(SHOP_ZONE).toInstant();
  }

  /** Last representable instant of {@code date}, for inclusive between-queries. */
  public static Instant endOfDayInclusive(LocalDate date) {
    return date.plusDays(1).atStartOfDay(SHOP_ZONE).toInstant().minusNanos(1);
  }

  public static boolean isWithin(LocalDate day, LocalDate from, LocalDate to) {
    return day != null && !day.isBefore(from) && !day.isAfter(to);
  }

  /** Leading characters of an id, for compact display alongside the full source id. */
  public static String shortId(String id) {
    if (!StringUtils.hasText(id)) {
      return MISSING_ID;
    }
    String trimmed = id.trim();
    return trimmed.length() <= SHORT_ID_LENGTH ? trimmed : trimmed.substring(0, SHORT_ID_LENGTH);
  }

  public static boolean containsIgnoreCase(String haystack, String lowercaseNeedle) {
    return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(lowercaseNeedle);
  }

  public static String lowerOrEmpty(String value) {
    return value != null ? value.toLowerCase(Locale.ROOT) : "";
  }

  /** Accumulates a signed amount against a vendor, ignoring unusable keys. */
  public static void addDelta(Map<String, BigDecimal> totals, String vendorId, BigDecimal delta) {
    if (!StringUtils.hasText(vendorId) || delta == null) {
      return;
    }
    totals.merge(vendorId, toMoneyScale(delta), BigDecimal::add);
  }
}
