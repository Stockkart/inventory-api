package com.inventory.pluginengine.menu;

/**
 * Department resolution for kitchen routing.
 *
 * <p>Resolution happens once, at punch time, and the result is then frozen onto the order line and
 * the ticket — a later menu edit must never change the department of an order that already exists.
 */
public final class MenuDepartments {

  public static final String DEFAULT = "KITCHEN";

  private MenuDepartments() {}

  /** Null or blank means the default kitchen. Values are trimmed and uppercased so grouping is stable. */
  public static String resolve(String raw) {
    if (raw == null || raw.isBlank()) {
      return DEFAULT;
    }
    return raw.trim().toUpperCase();
  }
}
