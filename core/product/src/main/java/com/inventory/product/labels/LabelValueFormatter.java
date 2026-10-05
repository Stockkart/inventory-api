package com.inventory.product.labels;

import com.inventory.pluginengine.ExtensionFieldCoercion;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Stateless formatting of raw label values into the strings printed on a sticker (Req 6.10).
 *
 * <p>Raw values may come from typed beans ({@code Product}, {@code Pricing}, {@code Shop}, {@code
 * Inventory}) or from loosely-typed vertical extension maps, so each {@link ValueType} accepts a
 * range of Java types plus their string representations. Anything that cannot be interpreted for a
 * typed category ({@code currency}, {@code date}, {@code percentage}) formats as {@code ""}; the
 * {@code number} category falls back to the trimmed string so free-form values are not lost.
 *
 * <p>Rules:
 *
 * <ul>
 *   <li>{@code null} → {@code ""} for every type.
 *   <li>{@code text}: {@code String.valueOf(raw).trim()}; enums use {@code name()}; collections join
 *       their elements with {@code ", "}.
 *   <li>{@code number}: {@code BigDecimal.stripTrailingZeros().toPlainString()} (e.g. {@code 10},
 *       {@code 2.5}); unparsable → trimmed string.
 *   <li>{@code currency}: {@code "₹" + setScale(2, HALF_UP).toPlainString()} with no grouping (e.g.
 *       {@code ₹120.00}); unparsable → {@code ""}.
 *   <li>{@code date}: {@code dd-MMM-yyyy} in {@link Locale#ENGLISH} at {@code Asia/Kolkata} (e.g.
 *       {@code 05-Mar-2026}); unparsable → {@code ""}.
 *   <li>{@code percentage}: {@code setScale(2, HALF_UP).stripTrailingZeros().toPlainString() + "%"}
 *       (e.g. {@code 12%}, {@code 12.5%}, {@code 12.25%}); unparsable → {@code ""}.
 * </ul>
 */
public final class LabelValueFormatter {

  static final ZoneId LABEL_ZONE = ZoneId.of("Asia/Kolkata");
  static final DateTimeFormatter DATE_FORMAT =
      DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);
  static final String RUPEE = "\u20B9";
  static final String RS_PREFIX = "Rs. ";

  private LabelValueFormatter() {}

  /**
   * Formats {@code raw} according to {@code type}. Never returns {@code null}.
   *
   * @param raw the source value; may be {@code null}
   * @param type the formatting category; {@code null} is treated as {@link ValueType#TEXT}
   * @return the printable string, or {@code ""} when there is nothing to print
   */
  public static String format(Object raw, ValueType type) {
    return format(raw, type, CurrencyStyle.RUPEE_SYMBOL);
  }

  /**
   * Formats {@code raw} according to {@code type}, rendering {@link ValueType#CURRENCY} values with
   * the given {@link CurrencyStyle} (Req 11): {@link CurrencyStyle#RUPEE_SYMBOL} produces {@code
   * ₹120.00}, {@link CurrencyStyle#RS_PREFIX} produces {@code Rs. 120.00}. The style is ignored for
   * every non-currency type. Never returns {@code null}.
   *
   * @param raw the source value; may be {@code null}
   * @param type the formatting category; {@code null} is treated as {@link ValueType#TEXT}
   * @param currencyStyle the currency rendering style; {@code null} is treated as {@link
   *     CurrencyStyle#RUPEE_SYMBOL}
   * @return the printable string, or {@code ""} when there is nothing to print
   */
  public static String format(Object raw, ValueType type, CurrencyStyle currencyStyle) {
    if (raw == null) {
      return "";
    }
    ValueType effective = type == null ? ValueType.TEXT : type;
    return switch (effective) {
      case TEXT -> formatText(raw);
      case NUMBER -> formatNumber(raw);
      case CURRENCY -> formatCurrency(raw, currencyStyle);
      case DATE -> formatDate(raw);
      case PERCENTAGE -> formatPercentage(raw);
    };
  }

  // ---------------------------------------------------------------------------------------------
  // text
  // ---------------------------------------------------------------------------------------------

  private static String formatText(Object raw) {
    if (raw instanceof Enum<?> e) {
      return e.name();
    }
    if (raw instanceof Collection<?> items) {
      return items.stream()
          .filter(Objects::nonNull)
          .map(LabelValueFormatter::formatText)
          .filter(s -> !s.isEmpty())
          .collect(Collectors.joining(", "));
    }
    return String.valueOf(raw).trim();
  }

  // ---------------------------------------------------------------------------------------------
  // number / currency / percentage
  // ---------------------------------------------------------------------------------------------

  private static String formatNumber(Object raw) {
    BigDecimal value = toBigDecimal(raw);
    if (value == null) {
      return String.valueOf(raw).trim();
    }
    return stripZeros(value).toPlainString();
  }

  private static String formatCurrency(Object raw, CurrencyStyle currencyStyle) {
    BigDecimal value = toBigDecimal(raw);
    if (value == null) {
      return "";
    }
    String prefix = currencyStyle == CurrencyStyle.RS_PREFIX ? RS_PREFIX : RUPEE;
    return prefix + value.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  private static String formatPercentage(Object raw) {
    BigDecimal value = toBigDecimal(raw);
    if (value == null) {
      return "";
    }
    return stripZeros(value.setScale(2, RoundingMode.HALF_UP)).toPlainString() + "%";
  }

  /** Strips trailing zeros, normalising any zero magnitude to plain {@code 0}. */
  private static BigDecimal stripZeros(BigDecimal value) {
    BigDecimal stripped = value.stripTrailingZeros();
    return stripped.signum() == 0 ? BigDecimal.ZERO : stripped;
  }

  /**
   * Coerces a numeric-ish value to {@link BigDecimal}. Typed {@link Number}s are converted
   * losslessly where the type allows; strings go through {@link ExtensionFieldCoercion}. Returns
   * {@code null} when the value cannot be interpreted as a number (including NaN/infinite doubles).
   */
  private static BigDecimal toBigDecimal(Object raw) {
    if (raw == null) {
      return null;
    }
    if (raw instanceof BigDecimal bd) {
      return bd;
    }
    if (raw instanceof BigInteger bi) {
      return new BigDecimal(bi);
    }
    if (raw instanceof Integer || raw instanceof Long || raw instanceof Short || raw instanceof Byte) {
      return BigDecimal.valueOf(((Number) raw).longValue());
    }
    if (raw instanceof Double || raw instanceof Float) {
      double d = ((Number) raw).doubleValue();
      if (Double.isNaN(d) || Double.isInfinite(d)) {
        return null;
      }
      // BigDecimal.valueOf(double) uses the canonical shortest decimal repr (2.5 → "2.5", not
      // 2.50000000000000001...), which is what callers expect for stored doubles.
      return BigDecimal.valueOf(d);
    }
    if (raw instanceof Number n) {
      try {
        return new BigDecimal(n.toString());
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    try {
      return ExtensionFieldCoercion.asBigDecimal(raw);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // date
  // ---------------------------------------------------------------------------------------------

  private static String formatDate(Object raw) {
    LocalDate date = toLocalDate(raw);
    return date == null ? "" : DATE_FORMAT.format(date);
  }

  /**
   * Resolves the calendar date to print, in {@link #LABEL_ZONE}. {@link LocalDate} and {@link
   * LocalDateTime} are already zone-less and are used as-is; instants and offset/zoned values are
   * shifted into the label zone first. Strings are tried as {@code Instant}, then {@code
   * LocalDate}, then {@code OffsetDateTime} (ISO-8601).
   */
  private static LocalDate toLocalDate(Object raw) {
    if (raw instanceof LocalDate ld) {
      return ld;
    }
    if (raw instanceof LocalDateTime ldt) {
      return ldt.toLocalDate();
    }
    if (raw instanceof Instant instant) {
      return instant.atZone(LABEL_ZONE).toLocalDate();
    }
    if (raw instanceof ZonedDateTime zdt) {
      return zdt.withZoneSameInstant(LABEL_ZONE).toLocalDate();
    }
    if (raw instanceof OffsetDateTime odt) {
      return odt.atZoneSameInstant(LABEL_ZONE).toLocalDate();
    }
    if (raw instanceof Date d) {
      return d.toInstant().atZone(LABEL_ZONE).toLocalDate();
    }
    String text = String.valueOf(raw).trim();
    if (text.isEmpty()) {
      return null;
    }
    try {
      return Instant.parse(text).atZone(LABEL_ZONE).toLocalDate();
    } catch (RuntimeException ignored) {
      // fall through
    }
    try {
      return LocalDate.parse(text);
    } catch (RuntimeException ignored) {
      // fall through
    }
    try {
      return OffsetDateTime.parse(text).atZoneSameInstant(LABEL_ZONE).toLocalDate();
    } catch (RuntimeException ignored) {
      // fall through
    }
    try {
      // Last resort: shared coercion helper (handles any additional accepted shapes).
      Instant coerced = ExtensionFieldCoercion.asInstant(text);
      return coerced == null ? null : coerced.atZone(LABEL_ZONE).toLocalDate();
    } catch (RuntimeException ignored) {
      return null;
    }
  }
}
