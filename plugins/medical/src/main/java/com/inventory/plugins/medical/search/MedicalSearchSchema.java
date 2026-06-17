package com.inventory.plugins.medical.search;

import com.inventory.pluginengine.defaultprovider.InventorySearchCursorMode;
import com.inventory.pluginengine.schema.VerticalEntitySchema;
import com.inventory.pluginengine.schema.VerticalEntitySearchConfig;
import com.inventory.pluginengine.schema.VerticalSchema;
import com.inventory.pluginengine.schema.VerticalSchemaField;
import com.inventory.pluginengine.schema.VerticalSearchSortField;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fallback schema search config when {@code InventorySearchQuery.schema} is not set (e.g. FEFO). */
public final class MedicalSearchSchema {

  private MedicalSearchSchema() {}

  public static VerticalSchema fallback() {
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
}
