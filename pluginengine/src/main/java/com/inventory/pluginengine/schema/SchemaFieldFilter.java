package com.inventory.pluginengine.schema;

import java.util.List;
import org.springframework.util.StringUtils;

public final class SchemaFieldFilter {

  private SchemaFieldFilter() {}

  /**
   * Filters entity fields for UI mode. {@code required: true} fields are always included.
   * Optional fields use {@code tier} ({@code regular} | {@code basic}) and {@code showIn}.
   */
  public static List<VerticalSchemaField> filterForMode(
      List<VerticalSchemaField> fields, SchemaDisplayMode mode) {
    if (fields == null || fields.isEmpty()) {
      return List.of();
    }
    return fields.stream().filter(f -> isVisibleInMode(f, mode)).toList();
  }

  static boolean isVisibleInMode(VerticalSchemaField field, SchemaDisplayMode mode) {
    if (mode == SchemaDisplayMode.INVOICE) {
      return field.getShowIn() != null && field.getShowIn().contains("invoice");
    }
    if (mode == SchemaDisplayMode.BASIC) {
      if (Boolean.TRUE.equals(field.getRequired())) {
        return field.getShowIn() == null
            || field.getShowIn().isEmpty()
            || field.getShowIn().contains("basic");
      }
      return "basic".equalsIgnoreCase(field.getTier())
          || (field.getShowIn() != null && field.getShowIn().contains("basic"));
    }
    // REGULAR
    if (Boolean.TRUE.equals(field.getRequired())) {
      return field.getShowIn() == null
          || field.getShowIn().isEmpty()
          || field.getShowIn().contains("registration");
    }
    if ("basic".equalsIgnoreCase(field.getTier())) {
      return field.getShowIn() != null && field.getShowIn().contains("basic");
    }
    return !StringUtils.hasText(field.getTier()) || "regular".equalsIgnoreCase(field.getTier());
  }
}
