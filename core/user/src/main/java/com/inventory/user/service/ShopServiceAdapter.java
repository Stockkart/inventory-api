package com.inventory.user.service;

/**
 * Adapter interface for accessing shop information from the product module.
 * This interface is implemented by the product module to avoid circular dependencies.
 */
public interface ShopServiceAdapter {
  
  /**
   * Get shop name by shop ID.
   * 
   * @param shopId the shop ID
   * @return shop name, or null if shop not found
   */
  String getShopName(String shopId);

  /**
   * Get shop owner/contact name (e.g. initialAdminName) by shop ID.
   *
   * @param shopId the shop ID
   * @return owner/contact name, or null if not found
   */
  String getShopOwnerName(String shopId);
  
  /**
   * Check if a shop exists by shop ID.
   * 
   * @param shopId the shop ID
   * @return true if shop exists, false otherwise
   */
  boolean shopExists(String shopId);
  
  /**
   * Shop summary for the login response.
   *
   * @param shopId the shop ID
   * @return the shop's summary, or null if the shop is not found
   */
  ShopSummary getShopSummary(String shopId);

  /** What the login response shows about the active shop. GST is per product, never per shop. */
  class ShopSummary {
    private String name;

    public ShopSummary() {}

    public ShopSummary(String name) {
      this.name = name;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }
  }
}
