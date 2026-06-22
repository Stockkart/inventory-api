package com.inventory.pluginengine.ref;

import org.springframework.util.StringUtils;

/** Typed sellable identity encoded as {@code kind:id} (e.g. {@code inventory:lot-uuid}, {@code menu:item-uuid}). */
public record SellableRef(String kind, String id) {

  public static final String KIND_INVENTORY = "inventory";
  public static final String KIND_MENU = "menu";

  private static final String SEPARATOR = ":";

  public SellableRef {
    if (!StringUtils.hasText(kind) || !StringUtils.hasText(id)) {
      throw new IllegalArgumentException("SellableRef kind and id are required");
    }
  }

  public static SellableRef inventory(String lotId) {
    return new SellableRef(KIND_INVENTORY, lotId.trim());
  }

  public static SellableRef menu(String menuItemId) {
    return new SellableRef(KIND_MENU, menuItemId.trim());
  }

  public String encode() {
    return kind + SEPARATOR + id;
  }

  public static SellableRef parse(String encoded) {
    if (!StringUtils.hasText(encoded)) {
      throw new IllegalArgumentException("sellableRef is required");
    }
    String trimmed = encoded.trim();
    int sep = trimmed.indexOf(SEPARATOR);
    if (sep <= 0 || sep >= trimmed.length() - 1) {
      throw new IllegalArgumentException("Invalid sellableRef: " + encoded);
    }
    return new SellableRef(trimmed.substring(0, sep), trimmed.substring(sep + 1));
  }

  public static SellableRef parseLenient(String encoded) {
    if (!StringUtils.hasText(encoded)) {
      return null;
    }
    try {
      return parse(encoded);
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  public boolean isInventory() {
    return KIND_INVENTORY.equals(kind);
  }

  public boolean isMenu() {
    return KIND_MENU.equals(kind);
  }
}
