package com.inventory.reminders.api.port;

import com.inventory.reminders.api.ExpiryBuckets;
import com.inventory.reminders.api.LotSummary;

/**
 * Inventory information reminders needs from the module that owns stock (product), without a
 * dependency on it.
 */
public interface InventoryPort {

  /** The lot's summary, or null when there is no such lot (or the id is null). */
  LotSummary getInventorySummary(String inventoryId);

  boolean inventoryExists(String inventoryId);

  /**
   * @param expiringSoonDays window for "expiring soon"; null or not positive means 30
   */
  ExpiryBuckets getExpiryBuckets(String shopId, Integer expiringSoonDays);
}
