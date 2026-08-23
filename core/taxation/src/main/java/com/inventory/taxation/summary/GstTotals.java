package com.inventory.taxation.summary;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The three sums every GST tab is headed with.
 *
 * <p>A tab holds one row per tax rate, so an invoice bearing two rates appears
 * twice. What that means for a header figure depends on what the figure is:
 *
 * <ul>
 *   <li>a <b>count</b> names a thing -- an invoice, a note, a bill of entry, an
 *       HSN code -- so it counts the distinct thing, not the rows;
 *   <li>a <b>taxable value or tax</b> is carried in per-rate shares that only
 *       add up to the whole when every row is summed;
 *   <li>an <b>invoice value</b> is stamped whole on each of the invoice's rows,
 *       so it is taken once per invoice.
 * </ul>
 *
 * <p>Every tab writer and tab DTO had written these three out by hand, which is
 * how the count came to be a row count at twenty sites at once. They live here
 * now so a tab states which of the three it wants and cannot spell it wrong.
 */
public final class GstTotals {

  private GstTotals() {}

  /** Adds a field over every row, reading an absent value as zero. */
  public static <T> BigDecimal sum(List<T> lines, Function<T, BigDecimal> field) {
    if (lines == null) {
      return BigDecimal.ZERO;
    }
    BigDecimal sum = BigDecimal.ZERO;
    for (T line : lines) {
      sum = sum.add(orZero(field.apply(line)));
    }
    return sum;
  }

  /** How many different things the rows name, ignoring the rows that name none. */
  public static <T> int countDistinct(List<T> lines, Function<T, String> field) {
    if (lines == null) {
      return 0;
    }
    return (int) lines.stream()
        .map(field)
        .filter(GstTotals::stated)
        .distinct()
        .count();
  }

  /**
   * Adds a value that repeats across a thing's rows, taking it once per thing.
   *
   * <p>The first row of an invoice settles its value; the rest repeat it and are
   * passed over. Summing them instead would charge a two-rate invoice twice --
   * on one real month that was 271,839 against the 240,704 actually filed.
   */
  public static <T> BigDecimal sumPerDistinct(
      List<T> lines, Function<T, String> key, Function<T, BigDecimal> value) {
    BigDecimal sum = BigDecimal.ZERO;
    for (BigDecimal once : valuePerKey(lines, key, value).values()) {
      sum = sum.add(once);
    }
    return sum;
  }

  /** The value each named thing carries, in the order the rows first name it. */
  public static <T> Map<String, BigDecimal> valuePerKey(
      List<T> lines, Function<T, String> key, Function<T, BigDecimal> value) {
    Map<String, BigDecimal> byKey = new LinkedHashMap<>();
    if (lines == null) {
      return byKey;
    }
    for (T line : lines) {
      String name = key.apply(line);
      if (!stated(name)) {
        continue;
      }
      byKey.putIfAbsent(name, orZero(value.apply(line)));
    }
    return byKey;
  }

  /** An absent amount is zero, not a hole in the total. */
  public static BigDecimal orZero(BigDecimal value) {
    return value != null ? value : BigDecimal.ZERO;
  }

  /** A blank name is no name at all. */
  public static boolean stated(String value) {
    return value != null && !value.isBlank();
  }
}
