package com.inventory.user.domain.model;

/**
 * StockKart-wide roles, independent of any shop role. A platform admin runs the catalogue, campaigns,
 * vouchers and approvals for every shop; there is no signup path to it.
 */
public enum PlatformRole {
  PLATFORM_ADMIN
}
