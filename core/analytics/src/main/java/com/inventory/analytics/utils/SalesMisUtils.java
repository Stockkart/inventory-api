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
 * Money, date and text helpers for the sales money MIS.
 *
 * <p>Customer-side counterpart of {@link VendorMoneyMisUtils}, following this module's convention of
 * one helper class per report. Kept separate so a change to the receivable report's rounding or
 * date handling cannot silently move the vendor report's numbers.
 */
public final class SalesMisUtils {

  private SalesMisUtils() {}

  /** Reports are read by shop staff in local time; ledger dates must not drift across UTC midnight. */
  public static final ZoneId SHOP_ZONE = ZoneId.of("Asia/Kolkata");

  /** Rupee amounts are presented at 2dp. */
  private static final int MONEY_SCALE = 2;

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

  public static boolean containsIgnoreCase(String haystack, String lowercaseNeedle) {
    return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(lowercaseNeedle);
  }

  public static String lowerOrEmpty(String value) {
    return value != null ? value.toLowerCase(Locale.ROOT) : "";
  }

  /** Accumulates a signed amount against a customer, ignoring unusable keys. */
  public static void addDelta(Map<String, BigDecimal> totals, String customerId, BigDecimal delta) {
    if (!StringUtils.hasText(customerId) || delta == null) {
      return;
    }
    totals.merge(customerId, toMoneyScale(delta), BigDecimal::add);
  }
}
