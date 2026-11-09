package com.inventory.product.gridlayout;

import com.inventory.pluginengine.schema.VerticalEntitySchema;
import com.inventory.pluginengine.schema.VerticalSchema;
import com.inventory.pluginengine.schema.VerticalSchemaField;
import java.util.List;
import java.util.function.Predicate;
import org.springframework.util.StringUtils;

/**
 * Turns vertical schema fields into grid columns keyed {@code field:<schemaKey>}, for any grid that
 * shows a vertical's own fields. A required field becomes a required (not hideable) column.
 */
public final class SchemaGridColumns {

  public static final String FIELD_PREFIX = "field:";

  private SchemaGridColumns() {}

  /** The fields of {@code entity} that pass {@code filter}, in schema order. */
  public static List<VerticalSchemaField> fields(
      VerticalSchema schema, String entity, Predicate<VerticalSchemaField> filter) {
    if (schema == null || schema.getEntities() == null) {
      return List.of();
    }
    VerticalEntitySchema entitySchema = schema.getEntities().get(entity);
    if (entitySchema == null || entitySchema.getFields() == null) {
      return List.of();
    }
    return entitySchema.getFields().stream()
        .filter(f -> f.getKey() != null)
        .filter(filter)
        .toList();
  }

  /** The column for one schema field; {@code forceRequired} for fields a workflow always needs. */
  public static GridColumnDefinition column(
      VerticalSchemaField field, int widthPx, boolean forceRequired) {
    String key = FIELD_PREFIX + field.getKey();
    String label = label(field);
    return forceRequired || Boolean.TRUE.equals(field.getRequired())
        ? GridColumnDefinition.required(key, label, widthPx)
        : GridColumnDefinition.optional(key, label, widthPx);
  }

  /** Same rule as the frontend's {@code fieldLabel}: the schema label, else the key in words. */
  public static String label(VerticalSchemaField field) {
    if (StringUtils.hasText(field.getLabel())) {
      return field.getLabel().trim();
    }
    String words = field.getKey().replaceAll("([A-Z])", " $1").trim();
    return words.isEmpty()
        ? field.getKey()
        : Character.toUpperCase(words.charAt(0)) + words.substring(1);
  }
}
