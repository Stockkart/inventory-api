package com.inventory.pluginengine.schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;

/** Applies declarative rules from {@link VerticalSchemaField#getValidation()} and field metadata. */
public final class SchemaFieldValidation {

  private SchemaFieldValidation() {}

  public static void validate(
      VerticalSchemaField field, Object value, boolean create, List<String> errors) {
    if (value == null) {
      return;
    }
    validateEnumValues(field, value, errors);
    Map<String, Object> rules = field.getValidation();
    if (rules == null || rules.isEmpty()) {
      return;
    }
    if (Boolean.TRUE.equals(rules.get("notPastOnCreate"))
        && create
        && value instanceof Instant expiry
        && expiry.isBefore(Instant.now())) {
      errors.add(field.getKey() + ": must not be in the past");
    }
    if (rules.containsKey("min") || rules.containsKey("max")) {
      BigDecimal numeric = toBigDecimal(value);
      if (numeric != null) {
        if (rules.containsKey("min")) {
          BigDecimal min = toBigDecimal(rules.get("min"));
          if (min != null && numeric.compareTo(min) < 0) {
            errors.add(field.getKey() + ": must be at least " + min);
          }
        }
        if (rules.containsKey("max")) {
          BigDecimal max = toBigDecimal(rules.get("max"));
          if (max != null && numeric.compareTo(max) > 0) {
            errors.add(field.getKey() + ": must be at most " + max);
          }
        }
      }
    }
    if (rules.containsKey("minLength") && value instanceof String s) {
      int minLength = toInt(rules.get("minLength"));
      if (minLength > 0 && s.length() < minLength) {
        errors.add(field.getKey() + ": must be at least " + minLength + " characters");
      }
    }
    if (rules.containsKey("maxLength") && value instanceof String s) {
      int maxLength = toInt(rules.get("maxLength"));
      if (maxLength > 0 && s.length() > maxLength) {
        errors.add(field.getKey() + ": must be at most " + maxLength + " characters");
      }
    }
  }

  private static void validateEnumValues(
      VerticalSchemaField field, Object value, List<String> errors) {
    if (!"enum".equalsIgnoreCase(field.getType())
        || field.getValues() == null
        || field.getValues().isEmpty()) {
      return;
    }
    String text = String.valueOf(value);
    if (!field.getValues().contains(text)) {
      errors.add(field.getKey() + ": must be one of " + field.getValues());
    }
  }

  private static BigDecimal toBigDecimal(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof BigDecimal bd) {
      return bd;
    }
    if (value instanceof Number n) {
      return BigDecimal.valueOf(n.doubleValue());
    }
    if (value instanceof String s && StringUtils.hasText(s)) {
      try {
        return new BigDecimal(s.trim());
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  private static int toInt(Object value) {
    if (value instanceof Number n) {
      return n.intValue();
    }
    if (value instanceof String s && StringUtils.hasText(s)) {
      try {
        return Integer.parseInt(s.trim());
      } catch (NumberFormatException ignored) {
        return 0;
      }
    }
    return 0;
  }
}
