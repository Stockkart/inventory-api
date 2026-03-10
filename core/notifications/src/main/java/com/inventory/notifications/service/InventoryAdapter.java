package com.inventory.notifications.service;

import com.inventory.notifications.rest.dto.response.ReminderInventorySummary;

/**
 * Adapter interface to expose Inventory information to other modules
 * without creating circular dependencies.
 */
public interface InventoryAdapter {

  ReminderInventorySummary getInventorySummary(String inventoryId);

  boolean inventoryExists(String inventoryId);
}
