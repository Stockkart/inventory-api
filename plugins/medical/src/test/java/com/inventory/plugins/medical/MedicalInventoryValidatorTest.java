package com.inventory.plugins.medical;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.InventoryVerticalValidator.InventoryValidationContext;
import com.inventory.pluginengine.SchemaDrivenInventoryValidator;
import com.inventory.pluginengine.schema.VerticalEntitySchema;
import com.inventory.pluginengine.schema.VerticalSchema;
import com.inventory.pluginengine.schema.VerticalSchemaField;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MedicalInventoryValidatorTest {

  private SchemaDrivenInventoryValidator validator;
  private VerticalSchema schema;

  @BeforeEach
  void setUp() {
    validator = new SchemaDrivenInventoryValidator("medical");
    schema = medicalSchema();
  }

  @Test
  void createSucceedsWithRequiredFields() {
    Instant future = Instant.now().plus(30, ChronoUnit.DAYS);
    InventoryValidationContext context =
        new InventoryValidationContext(
            "shop-1",
            "medical",
            "1.0.0",
            schema,
            Map.of(
                "name", "Paracetamol",
                "batchNo", "B001",
                "expiryDate", future),
            null);
    assertDoesNotThrow(() -> validator.validateCreate(context));
  }

  @Test
  void createFailsWhenBatchMissing() {
    Instant future = Instant.now().plus(30, ChronoUnit.DAYS);
    InventoryValidationContext context =
        new InventoryValidationContext(
            "shop-1",
            "medical",
            "1.0.0",
            schema,
            Map.of("name", "Paracetamol", "expiryDate", future),
            null);
    assertThrows(ValidationException.class, () -> validator.validateCreate(context));
  }

  @Test
  void createFailsWhenExpiryInPast() {
    Instant past = Instant.now().minus(1, ChronoUnit.DAYS);
    InventoryValidationContext context =
        new InventoryValidationContext(
            "shop-1",
            "medical",
            "1.0.0",
            schema,
            Map.of("name", "Paracetamol", "batchNo", "B001", "expiryDate", past),
            null);
    assertThrows(ValidationException.class, () -> validator.validateCreate(context));
  }

  @Test
  void createFailsWhenNameHasWrongType() {
    Instant future = Instant.now().plus(30, ChronoUnit.DAYS);
    InventoryValidationContext context =
        new InventoryValidationContext(
            "shop-1",
            "medical",
            "1.0.0",
            schema,
            Map.of(
                "name", 42,
                "batchNo", "B001",
                "expiryDate", future),
            null);
    assertThrows(ValidationException.class, () -> validator.validateCreate(context));
  }

  @Test
  void createFailsWhenBatchNoTooLong() {
    Instant future = Instant.now().plus(30, ChronoUnit.DAYS);
    InventoryValidationContext context =
        new InventoryValidationContext(
            "shop-1",
            "medical",
            "1.0.0",
            schema,
            Map.of(
                "name", "Paracetamol",
                "batchNo", "x".repeat(65),
                "expiryDate", future),
            null);
    assertThrows(ValidationException.class, () -> validator.validateCreate(context));
  }

  @Test
  void updateAllowsPastExpiryOnExistingStock() {
    Instant past = Instant.now().minus(1, ChronoUnit.DAYS);
    InventoryValidationContext context =
        new InventoryValidationContext(
            "shop-1",
            "medical",
            "1.0.0",
            schema,
            Map.of("name", "Paracetamol", "batchNo", "B001", "expiryDate", past),
            null);
    assertDoesNotThrow(() -> validator.validateUpdate(context));
  }

  private static VerticalSchema medicalSchema() {
    VerticalSchemaField name = field("name", "string", true);
    name.setValidation(Map.of("minLength", 1, "maxLength", 255));

    VerticalSchemaField batchNo = field("batchNo", "string", true);
    batchNo.setValidation(Map.of("minLength", 1, "maxLength", 64));

    VerticalSchemaField expiryDate = field("expiryDate", "date", true);
    expiryDate.setValidation(Map.of("notPastOnCreate", true));

    VerticalEntitySchema inventory = new VerticalEntitySchema();
    inventory.setFields(List.of(name, batchNo, expiryDate));

    VerticalSchema schema = new VerticalSchema();
    schema.setVerticalId("medical");
    schema.setVersion("1.0.0");
    schema.setEntities(Map.of("inventory", inventory));
    return schema;
  }

  private static VerticalSchemaField field(String key, String type, boolean required) {
    VerticalSchemaField f = new VerticalSchemaField();
    f.setKey(key);
    f.setType(type);
    f.setRequired(required);
    return f;
  }
}
