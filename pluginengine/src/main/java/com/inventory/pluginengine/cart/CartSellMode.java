package com.inventory.pluginengine.cart;

/** How a cart line affects inventory at checkout. */
public enum CartSellMode {
  /** Standard SKU billing — always deducts {@code inventoryId}. */
  SKU,
  /** Menu item billed only — no stock movement. */
  MENU,
  /** Menu item linked to inventory — deducts {@code inventoryId} at checkout. */
  DIRECT
}
