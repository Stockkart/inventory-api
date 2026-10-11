package com.inventory.pluginengine.integration;

import com.inventory.pluginengine.menu.MenuItem;
import java.util.Optional;

public interface ShopMenuLookup {

  Optional<MenuItem> findMenuItem(String shopId, String menuItemId);

  /** The direct (placed stock lot) menu item for this lot, if the shop's menu places it. */
  Optional<MenuItem> findDirectLink(String shopId, String inventoryId);
}
