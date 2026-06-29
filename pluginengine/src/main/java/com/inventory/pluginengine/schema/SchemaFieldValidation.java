package com.inventory.pluginengine.schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;

/** Applies type checks, enum metadata, and declarative rules from {@link VerticalSchemaField}. */
public final class SchemaFieldValidation {

  private SchemaFieldValidation() {}

  public static void validate(
      VerticalSchemaField field, Object value, boolean create, List<String> errors) {
    if (isAbsent(value)) {
      return;
    }
    if (!validateType(field, value, errors)) {
      return;
    }
    validateEnumValues(field, value, errors);
    applyDeclarativeRules(field, value, create, errors);
  }

  private static void applyDeclarativeRules(
      VerticalSchemaField field, Object value, boolean create, List<String> errors) {
    Map<String, Object> rules = field.getValidation();
    if (rules == null || rules.isEmpty()) {
      return;
    }
    String key = field.getKey();
    if (Boolean.TRUE.equals(rules.get("notPastOnCreate"))
        && create
        && toInstant(value) instanceof Instant expiry
        && expiry.isBefore(Instant.now())) {
      errors.add(key + ": must not be in the past");
    }
    if (rules.containsKey("min") || rules.containsKey("max")) {
      BigDecimal numeric = toBigDecimal(value);
      if (numeric != null) {
        if (rules.containsKey("min")) {
          BigDecimal min = toBigDecimal(rules.get("min"));
          if (min != null && numeric.compareTo(min) < 0) {
            errors.add(key + ": must be at least " + min);
          }
        }
        if (rules.containsKey("max")) {
          BigDecimal max = toBigDecimal(rules.get("max"));
          if (max != null && numeric.compareTo(max) > 0) {
            errors.add(key + ": must be at most " + max);
          }
        }
      }
    }
    if (rules.containsKey("minLength")) {
      String text = toText(value);
      if (text != null) {
        int minLength = toInt(rules.get("minLength"));
        if (minLength > 0 && text.length() < minLength) {
          errors.add(key + ": must be at least " + minLength + " characters");
        }
      }
    }
    if (rules.containsKey("maxLength")) {
      String text = toText(value);
      if (text != null) {
        int maxLength = toInt(rules.get("maxLength"));
        if (maxLength > 0 && text.length() > maxLength) {
          errors.add(key + ": must be at most " + maxLength + " characters");
        }
      }
    }
    if (rules.containsKey("pattern")) {
      String text = toText(value);
      Object pattern = rules.get("pattern");
      if (text != null && pattern instanceof String regex && StringUtils.hasText(regex)) {
        if (!text.matches(regex)) {
          errors.add(key + ": has invalid format");
        }
      }
    }
  }

  private static boolean validateType(
      VerticalSchemaField field, Object value, List<String> errors) {
    String type = field.getType();
    if (!StringUtils.hasText(type)) {
      return true;
    }
    String key = field.getKey();
    return switch (type.trim().toLowerCase()) {
      case "string" -> validateStringType(key, value, errors);
      case "date" -> validateDateType(key, value, errors);
      case "number", "money", "tax" -> validateNumberType(key, value, errors);
      case "enum" -> true;
      case "boolean" -> validateBooleanType(key, value, errors);
      default -> true;
    };
  }

  private static boolean validateStringType(String key, Object value, List<String> errors) {
    if (value instanceof CharSequence) {
      return true;
    }
    errors.add(key + ": must be a string");
    return false;
  }

  private static boolean validateDateType(String key, Object value, List<String> errors) {
    if (toInstant(value) != null) {
      return true;
    }
    errors.add(key + ": must be a valid date");
    return false;
  }

  private static boolean validateNumberType(String key, Object value, List<String> errors) {
    if (toBigDecimal(value) != null) {
      return true;
    }
    errors.add(key + ": must be a number");
    return false;
  }

  private static boolean validateBooleanType(String key, Object value, List<String> errors) {
    if (value instanceof Boolean) {
      return true;
    }
    if (value instanceof String s) {
      String normalized = s.trim().toLowerCase();
      if ("true".equals(normalized) || "false".equals(normalized)) {
        return true;
      }
    }
    errors.add(key + ": must be a boolean");
    return false;
  }

  private static void validateEnumValues(
      VerticalSchemaField field, Object value, List<String> errors) {
    if (!"enum".equalsIgnoreCase(field.getType())
        || field.getValues() == null
        || field.getValues().isEmpty()) {
      return;
    }
    String normalized = normalizeEnumToken(value);
    if (normalized == null) {
      errors.add(field.getKey() + ": must be one of " + field.getValues());
      return;
    }
    boolean allowed =
        field.getValues().stream()
            .map(SchemaFieldValidation::normalizeEnumToken)
            .anyMatch(normalized::equals);
    if (!allowed) {
      errors.add(field.getKey() + ": must be one of " + field.getValues());
    }
  }

  /** Maps yes/no and true/false enum representations to a common token for comparison. */
  private static String normalizeEnumToken(Object value) {
    String text = toText(value);
    if (text == null) {
      return null;
    }
    return switch (text.toLowerCase()) {
      case "true", "yes" -> "yes";
      case "false", "no" -> "no";
      default -> text;
    };
  }

  private static boolean isAbsent(Object value) {
    if (value == null) {
      return true;
    }
    if (value instanceof String s) {
      return !StringUtils.hasText(s);
    }
    return false;
  }

  private static String toText(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof CharSequence cs) {
      String text = cs.toString().trim();
      return StringUtils.hasText(text) ? text : null;
    }
    return String.valueOf(value);
  }

  private static Instant toInstant(Object value) {
    if (value instanceof Instant instant) {
      return instant;
    }
    if (value instanceof java.util.Date date) {
      return date.toInstant();
    }
    if (value instanceof CharSequence cs) {
      String text = cs.toString().trim();
      if (!StringUtils.hasText(text)) {
        return null;
      }
      try {
        return Instant.parse(text);
      } catch (DateTimeParseException ignored) {
        return null;
      }
    }
    return null;
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
