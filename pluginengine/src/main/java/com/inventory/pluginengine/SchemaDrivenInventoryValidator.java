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

/** Validates inventory payloads using schema {@code required} and {@code validation} metadata. */
public final class SchemaDrivenInventoryValidator implements InventoryVerticalValidator {

  private final String verticalLabel;

  public SchemaDrivenInventoryValidator(String verticalLabel) {
    this.verticalLabel = verticalLabel;
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
