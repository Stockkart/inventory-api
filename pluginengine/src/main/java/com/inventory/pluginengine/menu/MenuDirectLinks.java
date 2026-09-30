package com.inventory.pluginengine.menu;

import org.springframework.util.StringUtils;

/**
 * A direct menu item is a placement of a stock lot in a section. The lot owns price and tax, so a
 * direct item never stores any: whatever a client sends is cleared here, before validation, so the
 * validator and the stored document both see the item as it will be sold.
 */
public final class MenuDirectLinks {

  private MenuDirectLinks() {}

  public static boolean isDirect(MenuItem item) {
    return item != null && item.getSellMode() == MenuSellMode.direct;
  }

  public static void normalize(MenuItem item) {
    if (!isDirect(item)) {
      return;
    }
    item.setSellingPrice(null);
    item.setRates(null);
    item.setCgst(null);
    item.setSgst(null);
    item.setInventoryId(
        StringUtils.hasText(item.getInventoryId()) ? item.getInventoryId().trim() : null);
  }
}
