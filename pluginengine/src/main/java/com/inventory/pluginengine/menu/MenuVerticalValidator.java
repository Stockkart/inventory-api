package com.inventory.pluginengine.menu;

import com.inventory.pluginengine.schema.VerticalSchema;

/** Validates a {@link ShopMenu} document before persistence. */
public interface MenuVerticalValidator {

  String getVerticalId();

  void validate(ShopMenu menu, VerticalSchema schema, String shopId);
}
