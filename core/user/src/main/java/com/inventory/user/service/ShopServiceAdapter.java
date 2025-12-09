package com.inventory.user.service;

/**
 * Adapter interface to access Shop information without creating a circular dependency.
 * This will be implemented by the product module at runtime.
 */
public interface ShopServiceAdapter {
  /**
   * Get shop name by shop ID.
   *
   * @param shopId the shop ID
   * @return the shop name, or null if shop not found
   */
  String getShopName(String shopId);

  /**
   * Check if shop exists.
   *
   * @param shopId the shop ID
   * @return true if shop exists, false otherwise
   */
  boolean shopExists(String shopId);
}

