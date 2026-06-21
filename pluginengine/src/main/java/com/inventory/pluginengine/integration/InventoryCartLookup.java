package com.inventory.pluginengine.integration;

import java.util.Optional;

public interface InventoryCartLookup {

  Optional<InventoryLineSnapshot> findForShop(String shopId, String inventoryId);
}
