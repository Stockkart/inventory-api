package com.inventory.pluginengine.defaultprovider;

import org.springframework.util.StringUtils;

/** Well-known extension document field names used by default inventory search. */
public enum ExtensionDocumentField {
  INVENTORY_ID("inventoryId");

  private final String fieldName;

  ExtensionDocumentField(String fieldName) {
    this.fieldName = fieldName;
  }

  public String fieldName() {
    return fieldName;
  }

  public boolean matches(String key) {
    return key != null && fieldName.equals(key);
  }

  public static boolean isInventoryId(String key) {
    return INVENTORY_ID.matches(key);
  }

  public static String inventoryIdFieldName() {
    return INVENTORY_ID.fieldName();
  }
}
