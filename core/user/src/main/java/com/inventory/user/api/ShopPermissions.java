package com.inventory.user.api;

/** What a user may do in a shop, for other modules. */
public interface ShopPermissions {

  /** True when the user has access to the shop (a member, or a legacy user of that shop). */
  boolean hasAccess(String shopId, String userId);

  /** True when the user may edit the shop's rate rules and override or reset generated rates. */
  boolean canEditRates(String shopId, String userId);
}
