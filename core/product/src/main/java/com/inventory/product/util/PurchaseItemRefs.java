package com.inventory.product.util;

import com.inventory.pluginengine.ref.SellableRef;
import com.inventory.product.domain.model.PurchaseItem;
import org.springframework.util.StringUtils;

/** Normalizes and resolves {@link PurchaseItem} sellable/stock references. */
public final class PurchaseItemRefs {

  private PurchaseItemRefs() {}

  public static void normalize(PurchaseItem item) {
    if (item == null) {
      return;
    }
    if (StringUtils.hasText(item.getSellableRef())) {
      return;
    }
    String legacyMenu = readLegacyMenuItemId(item);
    String legacyInventory = readLegacyInventoryId(item);
    if (StringUtils.hasText(legacyMenu)) {
      item.setSellableRef(SellableRef.menu(legacyMenu).encode());
      if ("direct".equalsIgnoreCase(item.getSellMode()) && StringUtils.hasText(legacyInventory)) {
        item.setStockRef(SellableRef.inventory(legacyInventory).encode());
      }
      return;
    }
    if (StringUtils.hasText(legacyInventory)) {
      String ref = SellableRef.inventory(legacyInventory).encode();
      item.setSellableRef(ref);
      item.setStockRef(ref);
      if (!StringUtils.hasText(item.getSellMode())) {
        item.setSellMode("sku");
      }
    }
  }

  public static String lineKey(PurchaseItem item) {
    normalize(item);
    return item.getSellableRef();
  }

  public static boolean isMenuLine(PurchaseItem item) {
    normalize(item);
    SellableRef ref = SellableRef.parseLenient(item.getSellableRef());
    return ref != null && ref.isMenu();
  }

  /** Lot id to deduct for stock operations; null when line does not consume inventory. */
  public static String stockLotId(PurchaseItem item) {
    normalize(item);
    if (StringUtils.hasText(item.getStockRef())) {
      return SellableRef.parse(item.getStockRef()).id();
    }
    SellableRef sellable = SellableRef.parseLenient(item.getSellableRef());
    if (sellable != null && sellable.isInventory()) {
      return sellable.id();
    }
    return null;
  }

  public static void applyInventoryLine(PurchaseItem item, String lotId) {
    String ref = SellableRef.inventory(lotId).encode();
    item.setSellableRef(ref);
    item.setStockRef(ref);
    if (!StringUtils.hasText(item.getSellMode())) {
      item.setSellMode("sku");
    }
  }

  public static void applyMenuLine(
      PurchaseItem item, String menuItemId, String sellMode, String stockLotId) {
    item.setSellableRef(SellableRef.menu(menuItemId).encode());
    item.setSellMode(sellMode);
    if (StringUtils.hasText(stockLotId)) {
      item.setStockRef(SellableRef.inventory(stockLotId).encode());
    } else {
      item.setStockRef(null);
    }
  }

  public static String resolveSellableRefFromCartInput(
      String sellableRef, String legacyInventoryId, String legacyMenuItemId) {
    if (StringUtils.hasText(sellableRef)) {
      return sellableRef.trim();
    }
    if (StringUtils.hasText(legacyMenuItemId)) {
      return SellableRef.menu(legacyMenuItemId).encode();
    }
    if (StringUtils.hasText(legacyInventoryId)) {
      return SellableRef.inventory(legacyInventoryId).encode();
    }
    return null;
  }

  private static String readLegacyInventoryId(PurchaseItem item) {
    if (StringUtils.hasText(item.getMongoInventoryId())) {
      return item.getMongoInventoryId();
    }
    return null;
  }

  private static String readLegacyMenuItemId(PurchaseItem item) {
    if (StringUtils.hasText(item.getMongoMenuItemId())) {
      return item.getMongoMenuItemId();
    }
    return null;
  }
}
