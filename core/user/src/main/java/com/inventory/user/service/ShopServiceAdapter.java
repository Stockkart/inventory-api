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
   * Check if a shop exists by shop ID.
   * 
   * @param shopId the shop ID
   * @return true if shop exists, false otherwise
   */
  boolean shopExists(String shopId);
  
  /**
   * Get shop SGST and CGST values by shop ID.
   * 
   * @param shopId the shop ID
   * @return ShopTaxInfo containing sgst and cgst, or null if shop not found
   */
  ShopTaxInfo getShopTaxInfo(String shopId);
  
  /**
   * Data class for shop tax information.
   */
  class ShopTaxInfo {
    private String sgst;
    private String cgst;
    
    public ShopTaxInfo() {}
    
    public ShopTaxInfo(String sgst, String cgst) {
      this.sgst = sgst;
      this.cgst = cgst;
    }
    
    public String getSgst() {
      return sgst;
    }
    
    public void setSgst(String sgst) {
      this.sgst = sgst;
    }
    
    public String getCgst() {
      return cgst;
    }
    
    public void setCgst(String cgst) {
      this.cgst = cgst;
    }
  }
}
