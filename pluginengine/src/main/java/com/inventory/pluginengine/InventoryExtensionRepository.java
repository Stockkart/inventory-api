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

  /**
   * Ensures the backing {@code inventory_ext_<verticalId>} collection exists, creating it if absent.
   * Missing collections are not an error in MongoDB — reads silently return empty — so inventory
   * creation provisions the collection up front. Throws if the collection is absent and creation
   * fails.
   */
  void ensureBackingCollectionExists();

  Optional<Map<String, Object>> findByInventoryId(String shopId, String inventoryId);

  Map<String, Map<String, Object>> findByInventoryIds(String shopId, List<String> inventoryIds);

  void upsert(String shopId, String inventoryId, Map<String, Object> fields);
}
