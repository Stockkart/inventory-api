package com.inventory.pluginengine.schema;

import java.util.List;
import org.springframework.util.StringUtils;

public final class SchemaFieldFilter {

  private SchemaFieldFilter() {}

  /**
   * Filters entity fields for UI mode (regular vs basic). Mandatory ({@code required: true}) fields
   * are always included regardless of tier.
   */
  public static List<VerticalSchemaField> filterForMode(
      List<VerticalSchemaField> fields, SchemaDisplayMode mode) {
    if (fields == null || fields.isEmpty()) {
      return List.of();
    }
    return fields.stream().filter(f -> isVisibleInMode(f, mode)).toList();
  }

  static boolean isVisibleInMode(VerticalSchemaField field, SchemaDisplayMode mode) {
    if (Boolean.TRUE.equals(field.getRequired())) {
      return true;
    }
    if (mode == SchemaDisplayMode.INVOICE) {
      return field.getShowIn() != null && field.getShowIn().contains("invoice");
    }
    String tier = field.getTier();
    if (mode == SchemaDisplayMode.BASIC) {
      return "basic".equalsIgnoreCase(tier)
          || "mandatory".equalsIgnoreCase(tier)
          || (field.getShowIn() != null && field.getShowIn().contains("basic"));
    }
    return !StringUtils.hasText(tier)
        || "regular".equalsIgnoreCase(tier)
        || "mandatory".equalsIgnoreCase(tier);
  }
}
