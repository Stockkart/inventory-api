package com.inventory.plugins.sports;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.InventoryVerticalValidator.InventoryValidationContext;
import com.inventory.pluginengine.SchemaDrivenInventoryValidator;
import com.inventory.pluginengine.schema.VerticalEntitySchema;
import com.inventory.pluginengine.schema.VerticalSchema;
import com.inventory.pluginengine.schema.VerticalSchemaField;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SportsInventoryValidatorTest {

  private SchemaDrivenInventoryValidator validator;
  private VerticalSchema schema;

  @BeforeEach
  void setUp() {
    validator = new SchemaDrivenInventoryValidator("sports");
    schema = sportsSchema();
  }

  @Test
  void createSucceedsWithSportBrandModel() {
    InventoryValidationContext context =
        new InventoryValidationContext(
            "shop-1",
            "sports",
            "1.0.0",
            schema,
            Map.of(
                "name", "Cricket Bat",
                "baseUnit", "PCS",
                "sport", "cricket",
                "brand", "MRF",
                "model", "Genius Grand"));

    assertDoesNotThrow(() -> validator.validateCreate(context));
  }

  @Test
  void createFailsWhenSportMissing() {
    InventoryValidationContext context =
        new InventoryValidationContext(
            "shop-1",
            "sports",
            "1.0.0",
            schema,
            Map.of(
                "name", "Cricket Bat",
                "baseUnit", "PCS",
                "brand", "MRF",
                "model", "Genius Grand"));

    assertThrows(ValidationException.class, () -> validator.validateCreate(context));
  }

  @Test
  void createFailsWhenSportNotInEnum() {
    InventoryValidationContext context =
        new InventoryValidationContext(
            "shop-1",
            "sports",
            "1.0.0",
            schema,
            Map.of(
                "name", "Cricket Bat",
                "baseUnit", "PCS",
                "sport", "rugby",
                "brand", "MRF",
                "model", "Genius Grand"));

    assertThrows(ValidationException.class, () -> validator.validateCreate(context));
  }

  @Test
  void createFailsWhenBaseUnitMissing() {
    InventoryValidationContext context =
        new InventoryValidationContext(
            "shop-1",
            "sports",
            "1.0.0",
            schema,
            Map.of(
                "name", "Cricket Bat",
                "sport", "cricket",
                "brand", "MRF",
                "model", "Genius Grand"));

    assertThrows(ValidationException.class, () -> validator.validateCreate(context));
  }

  @Test
  void createFailsWhenWarrantyMonthsOutOfRange() {
    InventoryValidationContext context =
        new InventoryValidationContext(
            "shop-1",
            "sports",
            "1.0.0",
            schema,
            Map.of(
                "name", "Cricket Bat",
                "baseUnit", "PCS",
                "sport", "cricket",
                "brand", "MRF",
                "model", "Genius Grand",
                "warrantyMonths", 200));

    assertThrows(ValidationException.class, () -> validator.validateCreate(context));
  }

  private static VerticalSchema sportsSchema() {
    VerticalSchemaField name = field("name", "string", true);
    name.setValidation(Map.of("minLength", 1, "maxLength", 255));

    VerticalSchemaField sport = field("sport", "enum", true);
    sport.setValues(List.of("cricket", "football", "gym", "tennis", "badminton", "other"));

    VerticalSchemaField warrantyMonths = field("warrantyMonths", "number", false);
    warrantyMonths.setValidation(Map.of("min", 0, "max", 120));

    VerticalEntitySchema inventory = new VerticalEntitySchema();
    inventory.setFields(
        List.of(
            name,
            field("baseUnit", "string", true),
            sport,
            field("brand", "string", true),
            field("model", "string", true),
            warrantyMonths));

    VerticalSchema schema = new VerticalSchema();
    schema.setVerticalId("sports");
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
