package com.inventory.product.labels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class VerticalValueTypeMapperTest {

  private final VerticalValueTypeMapper mapper = new VerticalValueTypeMapper();

  @ParameterizedTest
  @CsvSource({
    "string, TEXT",
    "enum, TEXT",
    "number, NUMBER",
    "money, CURRENCY",
    "currency, CURRENCY",
    "date, DATE",
    "percent, PERCENTAGE",
    "percentage, PERCENTAGE"
  })
  void mapsKnownSchemaTypes(String schemaType, ValueType expected) {
    assertEquals(Optional.of(expected), mapper.map(schemaType));
  }

  @ParameterizedTest
  @CsvSource({"STRING, TEXT", "Money, CURRENCY", "' date ', DATE", "PerCent, PERCENTAGE"})
  void isCaseInsensitiveAndTrimsWhitespace(String schemaType, ValueType expected) {
    assertEquals(Optional.of(expected), mapper.map(schemaType));
  }

  @ParameterizedTest
  @ValueSource(strings = {"boolean", "list", "object", "file", "unknown"})
  void excludesNonPrintableTypes(String schemaType) {
    assertTrue(mapper.map(schemaType).isEmpty());
  }

  @ParameterizedTest
  @NullAndEmptySource
  void excludesNullAndEmpty(String schemaType) {
    assertTrue(mapper.map(schemaType).isEmpty());
  }

  @Test
  void excludesBlank() {
    assertTrue(mapper.map("   ").isEmpty());
  }
}
