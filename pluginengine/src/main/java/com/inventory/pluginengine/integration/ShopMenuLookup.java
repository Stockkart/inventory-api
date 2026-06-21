package com.inventory.pluginengine.integration;

import com.inventory.pluginengine.menu.MenuItem;
import java.util.Optional;

public interface ShopMenuLookup {

  Optional<MenuItem> findMenuItem(String shopId, String menuItemId);
}
