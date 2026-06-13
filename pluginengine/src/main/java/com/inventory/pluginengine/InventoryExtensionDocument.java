package com.inventory.pluginengine;

/**
 * Common shape for documents stored in {@code inventory_ext_<verticalId>} collections.
 */
public interface InventoryExtensionDocument {

  String getInventoryId();

  String getShopId();

  String getVerticalId();
}
