package com.inventory.pluginengine.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SchemaFieldFilterTest {

  @Test
  void regularModeIncludesMandatoryAndRegularFields() {
    List<VerticalSchemaField> fields =
        List.of(
            field("name", true, "mandatory"),
            field("batchNo", true, "mandatory"),
            field("schedule", false, "regular"),
            field("storageTemp", false, "regular"));

    List<VerticalSchemaField> filtered =
        SchemaFieldFilter.filterForMode(fields, SchemaDisplayMode.REGULAR);

    assertEquals(4, filtered.size());
  }

  @Test
  void invoiceModeIncludesInvoiceLineFieldsOnly() {
    List<VerticalSchemaField> fields =
        List.of(
            field("name", false, "mandatory", List.of("registration", "invoice")),
            field("schedule", false, "regular", List.of("registration")),
            field("qty", false, null, List.of("invoice")));

    List<VerticalSchemaField> filtered =
        SchemaFieldFilter.filterForMode(fields, SchemaDisplayMode.INVOICE);

    assertEquals(2, filtered.size());
    assertTrue(filtered.stream().anyMatch(f -> "name".equals(f.getKey())));
    assertTrue(filtered.stream().anyMatch(f -> "qty".equals(f.getKey())));
    assertTrue(filtered.stream().noneMatch(f -> "schedule".equals(f.getKey())));
  }

  @Test
  void basicModeIncludesMandatoryAndBasicOnly() {
    List<VerticalSchemaField> fields =
        List.of(
            field("name", true, "mandatory"),
            field("batchNo", true, "mandatory", List.of("registration", "basic")),
            field("schedule", false, "regular"),
            field("expiryDate", true, "mandatory", List.of("registration", "basic")));

    List<VerticalSchemaField> filtered =
        SchemaFieldFilter.filterForMode(fields, SchemaDisplayMode.BASIC);

    assertEquals(3, filtered.size());
    assertTrue(filtered.stream().anyMatch(f -> "batchNo".equals(f.getKey())));
    assertTrue(filtered.stream().noneMatch(f -> "schedule".equals(f.getKey())));
  }

  private static VerticalSchemaField field(String key, boolean required, String tier) {
    return field(key, required, tier, List.of("registration"));
  }

  private static VerticalSchemaField field(
      String key, boolean required, String tier, List<String> showIn) {
    VerticalSchemaField f = new VerticalSchemaField();
    f.setKey(key);
    f.setRequired(required);
    f.setTier(tier);
    f.setShowIn(showIn);
    return f;
  }
}
