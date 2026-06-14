package com.inventory.reminders.service;

import com.inventory.reminders.rest.dto.response.ReminderExpiryBucketsResponse;
import com.inventory.reminders.rest.dto.response.ReminderInventorySummary;

/**
 * Adapter interface to expose Inventory information to other modules
 * without creating circular dependencies.
 */
public interface InventoryAdapter {

  ReminderInventorySummary getInventorySummary(String inventoryId);

  boolean inventoryExists(String inventoryId);

  ReminderExpiryBucketsResponse getExpiryBuckets(String shopId, Integer expiringSoonDays);
}
