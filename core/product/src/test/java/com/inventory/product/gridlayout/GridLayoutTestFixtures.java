package com.inventory.product.gridlayout;

import com.inventory.pluginengine.schema.VerticalEntitySchema;
import com.inventory.pluginengine.schema.VerticalSchema;
import com.inventory.pluginengine.schema.VerticalSchemaField;
import java.util.List;
import java.util.Map;

/** A small medical-like schema: company, batch (required), expiry, sell-direct and a hidden core field. */
final class GridLayoutTestFixtures {

  private GridLayoutTestFixtures() {}

  static VerticalSchema schema() {
    VerticalEntitySchema inventory = new VerticalEntitySchema();
    inventory.setFields(
        List.of(
            field("companyName", "core", false, null, "text"),
            field("batchNo", "extension", true, List.of("invoice"), "text"),
            field("expiryDate", "extension", false, List.of("registration"), "date"),
            field("itemTypeDegree", "core", false, null, "text"),
            field("sellDirect", "extension", false, null, "select"),
            field("reorderLevel", "extension", false, List.of("search"), "number"),
            field("costPrice", "core", true, null, "number")));
    VerticalSchema schema = new VerticalSchema();
    schema.setEntities(Map.of("inventory", inventory));
    return schema;
  }

  static List<GridColumnDefinition> catalog() {
    return new GridColumnCatalog().columnsFor(GridIds.PRODUCT_ENTRY, schema());
  }

  private static VerticalSchemaField field(
      String key, String storage, boolean required, List<String> showIn, String type) {
    VerticalSchemaField f = new VerticalSchemaField();
    f.setKey(key);
    f.setStorage(storage);
    f.setRequired(required);
    f.setShowIn(showIn);
    f.setType(type);
    return f;
  }
}
