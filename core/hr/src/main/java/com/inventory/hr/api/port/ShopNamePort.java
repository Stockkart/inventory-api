package com.inventory.hr.api.port;

/**
 * The shop display name the Attendance app shows a worker while pairing and on the status screen.
 * Shops belong to a module hr does not depend on, so the implementation is wired above hr (see
 * {@code com.inventory.app.config.HrPortsConfig}).
 */
public interface ShopNamePort {

  /** The shop's display name, or null when the shop is unknown or has no name. */
  String getShopName(String shopId);
}
