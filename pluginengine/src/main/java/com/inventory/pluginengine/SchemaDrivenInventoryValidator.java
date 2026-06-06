package com.inventory.pluginengine;

import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.InventoryVerticalValidator.InventoryValidationContext;
import com.inventory.pluginengine.schema.VerticalEntitySchema;
import com.inventory.pluginengine.schema.VerticalSchema;
import com.inventory.pluginengine.schema.VerticalSchemaField;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;

/**
 * Validates required schema fields from a flat field map. Used by vertical plugins (medical, sports, …).
 */
public final class SchemaDrivenInventoryValidator implements InventoryVerticalValidator {

  @FunctionalInterface
  public interface FieldRule {
    void validate(
        String key, Object value, boolean create, List<String> errors, String verticalLabel);
  }

  private final String verticalLabel;
  private final Map<String, String> fieldAliases;
  private final List<FieldRule> customRules;

  public SchemaDrivenInventoryValidator(
      String verticalLabel, Map<String, String> fieldAliases, List<FieldRule> customRules) {
    this.verticalLabel = verticalLabel;
    this.fieldAliases = fieldAliases != null ? fieldAliases : Map.of();
    this.customRules = customRules != null ? customRules : List.of();
  }

  @Override
  public void validateCreate(InventoryValidationContext context) {
    validateRequiredFields(context, true);
  }

  @Override
  public void validateUpdate(InventoryValidationContext context) {
    validateRequiredFields(context, false);
  }

  private void validateRequiredFields(InventoryValidationContext context, boolean create) {
    Map<String, Object> fields = context.fields();
    VerticalSchema schema = context.schema();
    if (schema == null || schema.getEntities() == null) {
      return;
    }
    VerticalEntitySchema inventoryEntity = schema.getEntities().get("inventory");
    if (inventoryEntity == null || inventoryEntity.getFields() == null) {
      return;
    }

    List<String> errors = new ArrayList<>();
    for (VerticalSchemaField field : inventoryEntity.getFields()) {
      if (!Boolean.TRUE.equals(field.getRequired())) {
        continue;
      }
      String key = field.getKey();
      Object value = resolveFieldValue(key, fields);
      if (isMissing(value)) {
        errors.add(key + ": is required for " + verticalLabel + " inventory");
      }
      for (FieldRule rule : customRules) {
        rule.validate(key, value, create, errors, verticalLabel);
      }
    }

    if (!errors.isEmpty()) {
      throw new ValidationException(errors.stream().collect(java.util.stream.Collectors.toSet()));
    }
  }

  private Object resolveFieldValue(String schemaKey, Map<String, Object> fields) {
    if (fields.containsKey(schemaKey)) {
      return fields.get(schemaKey);
    }
    String alias = fieldAliases.get(schemaKey);
    if (alias != null) {
      return fields.get(alias);
    }
    return null;
  }

  private static boolean isMissing(Object value) {
    if (value == null) {
      return true;
    }
    if (value instanceof String s) {
      return !StringUtils.hasText(s);
    }
    return false;
  }

  public static FieldRule expiryNotInPastOnCreate() {
    return (key, value, create, errors, verticalLabel) -> {
      if ("expiryDate".equals(key) && create && value instanceof Instant expiry) {
        if (expiry.isBefore(Instant.now())) {
          errors.add("expiryDate: must not be in the past");
        }
      }
    };
  }
}
