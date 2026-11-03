package com.inventory.hr.utils;

import com.inventory.hr.domain.model.HrSettings;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Salary month ("pay cycle") of a shop. A shop picks the day 1–28 its salary month starts on
 * ({@code HrSettings.payCycleStartDay}, null = 1). A period stays a {@code YYYY-MM} string and is
 * named by the month the cycle ENDS in: with start day 26, period {@code 2026-10} covers 26 Sep –
 * 25 Oct 2026. Start day 1 is the calendar month (the behaviour before this setting existed).
 *
 * <p>Every payroll/lock use of a period goes through here; the attendance month register stays on
 * calendar months.
 */
public final class PayCycle {

  public static final int DEFAULT_START_DAY = 1;
  public static final int MIN_START_DAY = 1;
  public static final int MAX_START_DAY = 28;

  private static final DateTimeFormatter LABEL_DAY =
      DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
  private static final DateTimeFormatter LABEL_FULL =
      DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

  private PayCycle() {}

  /** Both ends inclusive. */
  public record Range(LocalDate from, LocalDate to) {

    /** Number of days in the cycle (28–31). */
    public int days() {
      return (int) (to.toEpochDay() - from.toEpochDay()) + 1;
    }

    public boolean contains(LocalDate date) {
      return !date.isBefore(from) && !date.isAfter(to);
    }

    public String fromText() {
      return HrDateUtils.format(from);
    }

    public String toText() {
      return HrDateUtils.format(to);
    }

    /** "26 Sep – 25 Oct 2026" ("1 Oct – 31 Oct 2026" for start day 1; year on both across years). */
    public String label() {
      return from.getYear() == to.getYear()
          ? from.format(LABEL_DAY) + " – " + to.format(LABEL_FULL)
          : from.format(LABEL_FULL) + " – " + to.format(LABEL_FULL);
    }
  }

  /** The shop's start day; null or out of range = 1. */
  public static int startDay(Integer stored) {
    return stored == null || stored < MIN_START_DAY || stored > MAX_START_DAY
        ? DEFAULT_START_DAY : stored;
  }

  public static int startDay(HrSettings settings) {
    return startDay(settings != null ? settings.getPayCycleStartDay() : null);
  }

  /**
   * Start 1: 1st – last day of {@code period}. Start d &gt; 1: day d of the previous month – day
   * d−1 of {@code period}.
   */
  public static Range range(YearMonth period, int startDay) {
    int d = startDay(startDay);
    if (d == 1) {
      return new Range(period.atDay(1), period.atEndOfMonth());
    }
    return new Range(period.minusMonths(1).atDay(d), period.atDay(d - 1));
  }

  /** {@link #range(YearMonth, int)} of a {@code YYYY-MM} string. */
  public static Range range(String period, int startDay) {
    return range(YearMonth.parse(period), startDay);
  }

  /**
   * The range stored on a run or line, or the calendar month of {@code period} for documents
   * written before pay cycles existed (start day 1 was the only behaviour then).
   */
  public static Range stored(String period, String fromDate, String toDate) {
    LocalDate from = HrDateUtils.parseDate(fromDate);
    LocalDate to = HrDateUtils.parseDate(toDate);
    return from != null && to != null ? new Range(from, to) : range(period, DEFAULT_START_DAY);
  }

  /** The period (named by its end month) whose cycle contains {@code date}. */
  public static YearMonth periodOf(LocalDate date, int startDay) {
    int d = startDay(startDay);
    YearMonth month = YearMonth.from(date);
    return d > 1 && date.getDayOfMonth() >= d ? month.plusMonths(1) : month;
  }

  /** {@code YYYY-MM} of the cycle containing a {@code yyyy-MM-dd} date. */
  public static String periodOf(String date, int startDay) {
    return periodOf(LocalDate.parse(date), startDay).toString();
  }

  /** The period that contains today (Asia/Kolkata). */
  public static String currentPeriod(int startDay) {
    return periodOf(HrDateUtils.today(), startDay).toString();
  }

  /** {@link #periodOf(String, int)} of every {@code yyyy-MM-dd} date, sorted and distinct. */
  public static Set<String> periodsOf(Collection<String> dates, int startDay) {
    Set<String> periods = new TreeSet<>();
    for (String date : dates) {
      periods.add(periodOf(date, startDay));
    }
    return periods;
  }

  /** Every period touching {@code [from, to]}, sorted. */
  public static Set<String> periodsBetween(LocalDate from, LocalDate to, int startDay) {
    Set<String> periods = new TreeSet<>();
    YearMonth last = periodOf(to, startDay);
    for (YearMonth p = periodOf(from, startDay); !p.isAfter(last); p = p.plusMonths(1)) {
      periods.add(p.toString());
    }
    return periods;
  }
}
