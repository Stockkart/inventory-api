package com.inventory.pluginengine.defaultprovider;

import com.inventory.common.exception.ValidationException;
import org.springframework.util.StringUtils;

/** Pagination strategy from {@code entities.inventory.search.cursor} in vertical schema JSON. */
public enum InventorySearchCursorMode {
  COMPOUND_KEY("compound-key"),
  SKIP("skip");

  private final String schemaValue;

  InventorySearchCursorMode(String schemaValue) {
    this.schemaValue = schemaValue;
  }

  public String schemaValue() {
    return schemaValue;
  }

  public static InventorySearchCursorMode fromSchema(String raw) {
    if (!StringUtils.hasText(raw)) {
      throw new ValidationException("Search cursor mode is required");
    }
    String value = raw.trim();
    for (InventorySearchCursorMode mode : values()) {
      if (mode.schemaValue.equalsIgnoreCase(value)) {
        return mode;
      }
    }
    throw new ValidationException("Unknown search cursor mode: " + raw);
  }
}
