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
  void notPastOnCreateRejectsPastExpiryOnCreate() {
    VerticalSchemaField field = new VerticalSchemaField();
    field.setKey("expiryDate");
    field.setValidation(Map.of("notPastOnCreate", true));

    List<String> errors = new ArrayList<>();
    SchemaFieldValidation.validate(
        field, Instant.now().minus(1, ChronoUnit.DAYS), true, errors);

    assertEquals(1, errors.size());
    assertTrue(errors.get(0).contains("must not be in the past"));
  }

  @Test
  void notPastOnCreateSkippedOnUpdate() {
    VerticalSchemaField field = new VerticalSchemaField();
    field.setKey("expiryDate");
    field.setValidation(Map.of("notPastOnCreate", true));

    List<String> errors = new ArrayList<>();
    SchemaFieldValidation.validate(
        field, Instant.now().minus(1, ChronoUnit.DAYS), false, errors);

    assertTrue(errors.isEmpty());
  }

  @Test
  void enumValuesMustMatchAllowedList() {
    VerticalSchemaField field = new VerticalSchemaField();
    field.setKey("sport");
    field.setType("enum");
    field.setValues(List.of("cricket", "football"));

    List<String> errors = new ArrayList<>();
    SchemaFieldValidation.validate(field, "gym", true, errors);

    assertEquals(1, errors.size());
    assertTrue(errors.get(0).contains("must be one of"));
  }

  @Test
  void minMaxValidatesNumbers() {
    VerticalSchemaField field = new VerticalSchemaField();
    field.setKey("warrantyMonths");
    field.setType("number");
    field.setValidation(Map.of("min", 0, "max", 120));

    List<String> errors = new ArrayList<>();
    SchemaFieldValidation.validate(field, 150, true, errors);

    assertEquals(1, errors.size());
    assertTrue(errors.get(0).contains("at most"));
  }
}
