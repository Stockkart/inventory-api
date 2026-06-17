package com.inventory.pluginengine.defaultprovider;

import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.schema.VerticalEntitySchema;
import com.inventory.pluginengine.schema.VerticalEntitySearchConfig;
import com.inventory.pluginengine.schema.VerticalSchema;
import com.inventory.pluginengine.schema.VerticalSchemaField;
import com.inventory.pluginengine.schema.VerticalSchemaStorage;
import com.inventory.pluginengine.schema.VerticalSearchSortField;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;

/** Resolves effective sort + cursor mode from vertical schema and optional request sort. */
public final class SchemaSearchConfigResolver {

  private SchemaSearchConfigResolver() {}

  public record ResolvedSearch(
      List<VerticalSearchSortField> sortFields,
      Map<String, VerticalSchemaField> fieldTypesByKey,
      InventorySearchCursorMode cursorMode) {}

  public static ResolvedSearch resolve(VerticalSchema schema, String sortParam) {
    if (schema == null) {
      throw new ValidationException("Vertical schema is required for extension search");
    }
    Map<String, VerticalSchemaField> fieldTypes = inventoryFieldIndex(schema);
    VerticalEntitySearchConfig config = inventorySearchConfig(schema);
    List<VerticalSearchSortField> sortFields =
        config != null && config.getDefaultSort() != null && !config.getDefaultSort().isEmpty()
            ? copySort(config.getDefaultSort())
            : inferDefaultSort(schema);

    if (StringUtils.hasText(sortParam)) {
      sortFields = applySortOverride(sortFields, sortParam.trim(), fieldTypes);
    }

    InventorySearchCursorMode cursorMode =
        config != null && StringUtils.hasText(config.getCursor())
            ? InventorySearchCursorMode.fromSchema(config.getCursor())
            : defaultCursorMode(sortFields, fieldTypes);
    return new ResolvedSearch(sortFields, fieldTypes, cursorMode);
  }

  private static List<VerticalSearchSortField> applySortOverride(
      List<VerticalSearchSortField> current,
      String sortParam,
      Map<String, VerticalSchemaField> fieldTypes) {
    String[] parts = sortParam.split(":", 2);
    String field = parts[0].trim();
    String direction = parts.length > 1 ? parts[1].trim().toLowerCase() : "asc";
    if (!"asc".equals(direction) && !"desc".equals(direction)) {
      throw new ValidationException("Invalid sort direction: " + direction);
    }
    validateSortable(field, fieldTypes);

    VerticalSearchSortField override = new VerticalSearchSortField();
    override.setField(field);
    override.setDirection(direction);
    VerticalSchemaField schemaField = fieldTypes.get(field);
    if (schemaField != null && "date".equalsIgnoreCase(schemaField.getType())) {
      override.setNulls("last");
    }

    List<VerticalSearchSortField> next = new ArrayList<>();
    next.add(override);
    for (VerticalSearchSortField existing : current) {
      if (!field.equals(existing.getField())) {
        next.add(copyField(existing));
      }
    }
    return next;
  }

  private static void validateSortable(String field, Map<String, VerticalSchemaField> fieldTypes) {
    VerticalSchemaField schemaField = fieldTypes.get(field);
    if (schemaField == null) {
      throw new ValidationException("Unsupported sort field: " + field);
    }
    if (!Boolean.TRUE.equals(schemaField.getSortable())) {
      throw new ValidationException("Field is not sortable: " + field);
    }
  }

  private static List<VerticalSearchSortField> inferDefaultSort(VerticalSchema schema) {
    List<VerticalSearchSortField> sort = new ArrayList<>();
    for (VerticalSchemaField field : VerticalSchemaStorage.inventoryFields(schema)) {
      if (Boolean.TRUE.equals(field.getSortable())) {
        VerticalSearchSortField spec = new VerticalSearchSortField();
        spec.setField(field.getKey());
        spec.setDirection("asc");
        if ("date".equalsIgnoreCase(field.getType())) {
          spec.setNulls("last");
        }
        sort.add(spec);
      }
    }
    return sort;
  }

  private static InventorySearchCursorMode defaultCursorMode(
      List<VerticalSearchSortField> sortFields, Map<String, VerticalSchemaField> fieldTypes) {
    for (VerticalSearchSortField sortField : sortFields) {
      VerticalSchemaField field = fieldTypes.get(sortField.getField());
      if (field != null && "date".equalsIgnoreCase(field.getType())) {
        return InventorySearchCursorMode.COMPOUND_KEY;
      }
    }
    return InventorySearchCursorMode.SKIP;
  }

  private static VerticalEntitySearchConfig inventorySearchConfig(VerticalSchema schema) {
    if (schema.getEntities() == null) {
      return null;
    }
    VerticalEntitySchema inventory = schema.getEntities().get("inventory");
    return inventory != null ? inventory.getSearch() : null;
  }

  private static Map<String, VerticalSchemaField> inventoryFieldIndex(VerticalSchema schema) {
    Map<String, VerticalSchemaField> index = new LinkedHashMap<>();
    for (VerticalSchemaField field : VerticalSchemaStorage.inventoryFields(schema)) {
      index.put(field.getKey(), field);
    }
    return index;
  }

  private static List<VerticalSearchSortField> copySort(List<VerticalSearchSortField> source) {
    List<VerticalSearchSortField> copy = new ArrayList<>();
    for (VerticalSearchSortField field : source) {
      copy.add(copyField(field));
    }
    return copy;
  }

  private static VerticalSearchSortField copyField(VerticalSearchSortField source) {
    VerticalSearchSortField copy = new VerticalSearchSortField();
    copy.setField(source.getField());
    copy.setDirection(
        StringUtils.hasText(source.getDirection()) ? source.getDirection() : "asc");
    copy.setNulls(source.getNulls());
    return copy;
  }
}
