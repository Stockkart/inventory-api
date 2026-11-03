package com.inventory.reminders.api;

/** Records events for the shop's notification feed and pushes them to connected clients. */
public interface InventoryAlerts {

  /** Stores an inventory-low event and broadcasts it to the shop's open event streams. */
  void recordAndBroadcastInventoryLow(InventoryLowAlert alert);
}
