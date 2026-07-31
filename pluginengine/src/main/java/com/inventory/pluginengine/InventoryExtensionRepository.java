package com.inventory.pluginengine;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Port for vertical inventory extension storage. Core product resolves the implementation via
 * {@link VerticalPlugin#getInventoryExtensionRepository()} — never by concrete plugin class.
 */
public interface InventoryExtensionRepository {

  String getVerticalId();

  Optional<Map<String, Object>> findByInventoryId(String shopId, String inventoryId);

  Map<String, Map<String, Object>> findByInventoryIds(String shopId, List<String> inventoryIds);

  void upsert(String shopId, String inventoryId, Map<String, Object> fields);
}
