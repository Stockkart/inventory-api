package com.inventory.pluginengine;

import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.InventoryVerticalValidator.InventoryValidationContext;
import com.inventory.pluginengine.schema.SchemaFieldValidation;
import com.inventory.pluginengine.schema.VerticalEntitySchema;
import com.inventory.pluginengine.schema.VerticalSchema;
import com.inventory.pluginengine.schema.VerticalSchemaField;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;

/**
 * Validates inventory payloads using schema {@code required}, {@code validation}, and optional
 * plugin {@link FieldRule} overrides.
 */
public final class SchemaDrivenInventoryValidator implements InventoryVerticalValidator {

  @FunctionalInterface
  public interface FieldRule {
    void validate(
        String key, Object value, boolean create, List<String> errors, String verticalLabel);
  }

  private final String verticalLabel;
  private final List<FieldRule> customRules;

  public SchemaDrivenInventoryValidator(String verticalLabel) {
    this(verticalLabel, List.of());
  }

  public SchemaDrivenInventoryValidator(String verticalLabel, List<FieldRule> customRules) {
    this.verticalLabel = verticalLabel;
    this.customRules = customRules != null ? customRules : List.of();
  }

  @Override
  public void validateCreate(InventoryValidationContext context) {
    validateFields(context, true);
  }

  @Override
  public void validateUpdate(InventoryValidationContext context) {
    validateFields(context, false);
  }

  private void validateFields(InventoryValidationContext context, boolean create) {
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
      String key = field.getKey();
      Object value = fields.get(key);

      if (Boolean.TRUE.equals(field.getRequired()) && isMissing(value)) {
        errors.add(key + ": is required for " + verticalLabel + " inventory");
      } else {
        SchemaFieldValidation.validate(field, value, create, errors);
      }

      for (FieldRule rule : customRules) {
        rule.validate(key, value, create, errors, verticalLabel);
      }
    }

    if (!errors.isEmpty()) {
      throw new ValidationException(errors.stream().collect(java.util.stream.Collectors.toSet()));
    }
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
}
