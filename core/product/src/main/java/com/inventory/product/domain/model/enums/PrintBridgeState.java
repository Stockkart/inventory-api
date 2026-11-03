package com.inventory.product.domain.model.enums;

/** What the shop's print bridge is, compared with the release StockKart ships. */
public enum PrintBridgeState {
  /** The browser could not reach a bridge on this computer: install it. */
  NOT_DETECTED,
  /** A bridge answered, older than the minimum supported release. It still prints: update it. */
  OUTDATED,
  /** A bridge answered at or above the minimum supported release. */
  CONNECTED
}
