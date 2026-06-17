package com.inventory.pluginengine.defaultprovider;

import com.inventory.pluginengine.schema.VerticalSchema;
import com.inventory.pluginengine.schema.VerticalSchemaField;
import com.inventory.pluginengine.schema.VerticalSchemaStorage;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.util.StringUtils;

/** Applies equality filters for schema {@code searchable} extension fields. */
public final class SchemaExtensionSearchFilters {

  private SchemaExtensionSearchFilters() {}

  public static void applySearchableEquals(
      Criteria criteria, Map<String, String> filters, VerticalSchema schema) {
    if (filters == null || filters.isEmpty()) {
      return;
    }
    Map<String, VerticalSchemaField> searchable = searchableExtensionFields(schema);
    filters.forEach(
        (key, raw) -> {
          if (!StringUtils.hasText(raw) || !searchable.containsKey(key)) {
            return;
          }
          criteria.and(key).is(raw.trim());
        });
  }

  private static Map<String, VerticalSchemaField> searchableExtensionFields(VerticalSchema schema) {
    Map<String, VerticalSchemaField> out = new LinkedHashMap<>();
    for (VerticalSchemaField field : VerticalSchemaStorage.inventoryFields(schema)) {
      if (Boolean.TRUE.equals(field.getSearchable())
          && VerticalSchemaStorage.STORAGE_EXTENSION.equalsIgnoreCase(
              field.getStorage() != null ? field.getStorage().trim() : "")) {
        out.put(field.getKey(), field);
      }
    }
    return out;
  }
}
