package com.inventory.pluginengine;

/**
 * Indexed search on {@code inventory_ext_<vertical>} collections.
 * Returns core inventory ids for batch hydration by {@code InventoryService}.
 */
public interface InventorySearchProvider {

  String getVerticalId();

  InventorySearchResult search(String shopId, InventorySearchQuery query);

  /**
   * Pre-aggregated expiry counts using extension indexes. Returns {@code null} when vertical has no
   * expiry field.
   */
  default InventoryExpiryBucketSummary aggregateExpiryBuckets(String shopId, int expiringSoonDays) {
    return null;
  }

  /** FEFO-ordered inventory ids (expiry ASC). Optional {@code batchNo} narrows to one batch. */
  default InventorySearchResult searchFefo(
      String shopId, String batchNo, int limit) {
    return InventorySearchResult.builder().build();
  }

  /**
   * Prefix match on lot {@code batchNo} (extension). Used by the free-text search box so a batch
   * id can be typed without the word {@code batch}.
   */
  default java.util.List<String> findInventoryIdsByBatchPrefix(String shopId, String prefix) {
    return java.util.List.of();
  }
}
