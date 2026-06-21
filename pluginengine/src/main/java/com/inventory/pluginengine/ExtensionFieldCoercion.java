package com.inventory.pluginengine;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import org.springframework.util.StringUtils;

public final class ExtensionFieldCoercion {

  private ExtensionFieldCoercion() {}

  public static String asString(Object value) {
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value).trim();
    return text.isEmpty() ? null : text;
  }

  public static Integer asInteger(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return number.intValue();
    }
    String text = String.valueOf(value).trim();
    if (!StringUtils.hasText(text)) {
      return null;
    }
    return Integer.valueOf(text);
  }

  public static BigDecimal asBigDecimal(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof BigDecimal decimal) {
      return decimal;
    }
    if (value instanceof Number number) {
      return BigDecimal.valueOf(number.doubleValue());
    }
    String text = String.valueOf(value).trim();
    if (!StringUtils.hasText(text)) {
      return null;
    }
    return new BigDecimal(text);
  }

  public static Instant asInstant(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Instant instant) {
      return instant;
    }
    if (value instanceof java.util.Date date) {
      return date.toInstant();
    }
    String text = String.valueOf(value).trim();
    if (!StringUtils.hasText(text)) {
      return null;
    }
    try {
      return Instant.parse(text);
    } catch (DateTimeParseException ignored) {
      return LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
  }
}
