package com.inventory.pluginengine.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SchemaFieldFilterTest {

  @Test
  void regularModeIncludesRequiredAndRegularOptionalFields() {
    List<VerticalSchemaField> fields =
        List.of(
            field("name", true, null),
            field("batchNo", true, null),
            field("companyName", false, "regular"),
            field("storageTemp", false, "regular"));

    List<VerticalSchemaField> filtered =
        SchemaFieldFilter.filterForMode(fields, SchemaDisplayMode.REGULAR);

    assertEquals(4, filtered.size());
  }

  @Test
  void invoiceModeIncludesInvoiceTaggedFieldsOnly() {
    List<VerticalSchemaField> fields =
        List.of(
            field("name", false, null, List.of("registration", "invoice")),
            field("companyName", false, "regular", List.of("registration")),
            field("batchNo", false, null, List.of("invoice")));

    List<VerticalSchemaField> filtered =
        SchemaFieldFilter.filterForMode(fields, SchemaDisplayMode.INVOICE);

    assertEquals(2, filtered.size());
    assertTrue(filtered.stream().anyMatch(f -> "name".equals(f.getKey())));
    assertTrue(filtered.stream().anyMatch(f -> "batchNo".equals(f.getKey())));
    assertTrue(filtered.stream().noneMatch(f -> "companyName".equals(f.getKey())));
  }

  @Test
  void basicModeIncludesRequiredAndBasicTaggedFields() {
    List<VerticalSchemaField> fields =
        List.of(
            field("name", true, null),
            field("batchNo", true, null, List.of("registration")),
            field("companyName", false, null, List.of("registration", "basic")),
            field("expiryDate", true, null, List.of("registration", "basic")));

    List<VerticalSchemaField> filtered =
        SchemaFieldFilter.filterForMode(fields, SchemaDisplayMode.BASIC);

    assertEquals(2, filtered.size());
    assertTrue(filtered.stream().noneMatch(f -> "batchNo".equals(f.getKey())));
    assertTrue(filtered.stream().anyMatch(f -> "companyName".equals(f.getKey())));
    assertTrue(filtered.stream().anyMatch(f -> "expiryDate".equals(f.getKey())));
  }

  @Test
  void regularModeHidesBasicTierOptionalFieldsUnlessShowInBasic() {
    VerticalSchemaField basicOnly = field("quickNote", false, "basic");
    basicOnly.setShowIn(List.of("registration"));

    List<VerticalSchemaField> filtered =
        SchemaFieldFilter.filterForMode(List.of(basicOnly), SchemaDisplayMode.REGULAR);

    assertEquals(0, filtered.size());
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
