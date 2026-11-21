package com.inventory.reminders.api;

/** A lot that has fallen to or below its low-stock threshold. */
public record InventoryLowAlert(
    String shopId, String inventoryId, String productName, Integer currentCount, Integer threshold) {}
