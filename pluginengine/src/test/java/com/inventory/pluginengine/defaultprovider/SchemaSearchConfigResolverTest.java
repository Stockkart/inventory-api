package com.inventory.pluginengine.defaultprovider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.schema.VerticalEntitySchema;
import com.inventory.pluginengine.schema.VerticalEntitySearchConfig;
import com.inventory.pluginengine.schema.VerticalSchema;
import com.inventory.pluginengine.schema.VerticalSchemaField;
import com.inventory.pluginengine.schema.VerticalSearchSortField;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SchemaSearchConfigResolverTest {

  @Test
  void resolvesDefaultSortFromSchemaSearchBlock() {
    VerticalSchema schema = medicalSchema();
    SchemaSearchConfigResolver.ResolvedSearch resolved =
        SchemaSearchConfigResolver.resolve(schema, null);

    assertEquals(InventorySearchCursorMode.COMPOUND_KEY, resolved.cursorMode());
    assertEquals(1, resolved.sortFields().size());
    assertEquals("expiryDate", resolved.sortFields().get(0).getField());
  }

  @Test
  void appliesSortOverrideWhenFieldIsSortable() {
    VerticalSchema schema = medicalSchema();
    SchemaSearchConfigResolver.ResolvedSearch resolved =
        SchemaSearchConfigResolver.resolve(schema, "expiryDate:asc");

    assertEquals(1, resolved.sortFields().size());
    assertEquals("expiryDate", resolved.sortFields().get(0).getField());
  }

  @Test
  void rejectsUnsupportedSortField() {
    VerticalSchema schema = medicalSchema();
    assertThrows(
        ValidationException.class,
        () -> SchemaSearchConfigResolver.resolve(schema, "brand:asc"));
  }

  @Test
  void infersSkipCursorForNonDateSort() {
    VerticalSchema schema = sportsSchema();
    SchemaSearchConfigResolver.ResolvedSearch resolved =
        SchemaSearchConfigResolver.resolve(schema, null);
    assertEquals(InventorySearchCursorMode.SKIP, resolved.cursorMode());
    assertEquals("brand", resolved.sortFields().get(0).getField());
    assertEquals("sport", resolved.sortFields().get(1).getField());
  }

  @Test
  void parsesCursorModeFromSchemaJson() {
    assertEquals(
        InventorySearchCursorMode.COMPOUND_KEY,
        InventorySearchCursorMode.fromSchema("compound-key"));
    assertEquals(InventorySearchCursorMode.SKIP, InventorySearchCursorMode.fromSchema("skip"));
  }

  private static VerticalSchema medicalSchema() {
    VerticalSchema schema = new VerticalSchema();
    schema.setVerticalId("medical");

    VerticalSchemaField expiry = new VerticalSchemaField();
    expiry.setKey("expiryDate");
    expiry.setType("date");
    expiry.setSortable(true);

    VerticalSearchSortField expirySort = new VerticalSearchSortField();
    expirySort.setField("expiryDate");
    expirySort.setDirection("asc");
    expirySort.setNulls("last");

    VerticalEntitySearchConfig search = new VerticalEntitySearchConfig();
    search.setDefaultSort(List.of(expirySort));
    search.setCursor(InventorySearchCursorMode.COMPOUND_KEY.schemaValue());

    VerticalEntitySchema inventory = new VerticalEntitySchema();
    inventory.setFields(List.of(expiry));
    inventory.setSearch(search);

    Map<String, VerticalEntitySchema> entities = new LinkedHashMap<>();
    entities.put("inventory", inventory);
    schema.setEntities(entities);
    return schema;
  }

  private static VerticalSchema sportsSchema() {
    VerticalSchema schema = new VerticalSchema();
    schema.setVerticalId("sports");

    VerticalSearchSortField brand = new VerticalSearchSortField();
    brand.setField("brand");
    brand.setDirection("asc");
    VerticalSearchSortField sport = new VerticalSearchSortField();
    sport.setField("sport");
    sport.setDirection("asc");

    VerticalEntitySearchConfig search = new VerticalEntitySearchConfig();
    search.setDefaultSort(List.of(brand, sport));
    search.setCursor(InventorySearchCursorMode.SKIP.schemaValue());

    VerticalEntitySchema inventory = new VerticalEntitySchema();
    inventory.setSearch(search);

    Map<String, VerticalEntitySchema> entities = new LinkedHashMap<>();
    entities.put("inventory", inventory);
    schema.setEntities(entities);
    return schema;
  }
}
