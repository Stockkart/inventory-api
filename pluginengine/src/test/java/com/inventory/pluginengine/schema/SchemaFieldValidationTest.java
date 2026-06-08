package com.inventory.pluginengine.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SchemaFieldValidationTest {

  @Test
  void stringTypeRejectsNonStringValues() {
    VerticalSchemaField field = field("name", "string");

    List<String> errors = new ArrayList<>();
    SchemaFieldValidation.validate(field, List.of("bad"), true, errors);

    assertEquals(1, errors.size());
    assertTrue(errors.get(0).contains("must be a string"));
  }

  @Test
  void dateTypeRequiresValidDate() {
    VerticalSchemaField field = field("expiryDate", "date");

    List<String> errors = new ArrayList<>();
    SchemaFieldValidation.validate(field, "not-a-date", true, errors);

    assertEquals(1, errors.size());
    assertTrue(errors.get(0).contains("must be a valid date"));
  }

  @Test
  void numberTypeRequiresNumericValue() {
    VerticalSchemaField field = field("warrantyMonths", "number");

    List<String> errors = new ArrayList<>();
    SchemaFieldValidation.validate(field, "abc", true, errors);

    assertEquals(1, errors.size());
    assertTrue(errors.get(0).contains("must be a number"));
  }

  @Test
  void notPastOnCreateRejectsPastExpiryOnCreate() {
    VerticalSchemaField field = field("expiryDate", "date");
    field.setValidation(Map.of("notPastOnCreate", true));

    List<String> errors = new ArrayList<>();
    SchemaFieldValidation.validate(
        field, Instant.now().minus(1, ChronoUnit.DAYS), true, errors);

    assertEquals(1, errors.size());
    assertTrue(errors.get(0).contains("must not be in the past"));
  }

  @Test
  void notPastOnCreateSkippedOnUpdate() {
    VerticalSchemaField field = field("expiryDate", "date");
    field.setValidation(Map.of("notPastOnCreate", true));

    List<String> errors = new ArrayList<>();
    SchemaFieldValidation.validate(
        field, Instant.now().minus(1, ChronoUnit.DAYS), false, errors);

    assertTrue(errors.isEmpty());
  }

  @Test
  void enumValuesMustMatchAllowedList() {
    VerticalSchemaField field = field("sport", "enum");
    field.setValues(List.of("cricket", "football"));

    List<String> errors = new ArrayList<>();
    SchemaFieldValidation.validate(field, "gym", true, errors);

    assertEquals(1, errors.size());
    assertTrue(errors.get(0).contains("must be one of"));
  }

  @Test
  void minMaxValidatesNumbers() {
    VerticalSchemaField field = field("warrantyMonths", "number");
    field.setValidation(Map.of("min", 0, "max", 120));

    List<String> errors = new ArrayList<>();
    SchemaFieldValidation.validate(field, 150, true, errors);

    assertEquals(1, errors.size());
    assertTrue(errors.get(0).contains("at most"));
  }

  @Test
  void maxLengthValidatesStringsWithoutExplicitValidationBlockOnOtherFields() {
    VerticalSchemaField field = field("batchNo", "string");
    field.setValidation(Map.of("maxLength", 64));

    List<String> errors = new ArrayList<>();
    SchemaFieldValidation.validate(field, "x".repeat(65), true, errors);

    assertEquals(1, errors.size());
    assertTrue(errors.get(0).contains("at most 64 characters"));
  }

  private static VerticalSchemaField field(String key, String type) {
    VerticalSchemaField field = new VerticalSchemaField();
    field.setKey(key);
    field.setType(type);
    return field;
  }
}
