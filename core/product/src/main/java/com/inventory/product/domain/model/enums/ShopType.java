package com.inventory.product.domain.model.enums;

/**
 * Type of shop in the supply chain.
 * - RETAILER: Sells to end consumers. Default price is MRP (tax-inclusive).
 * - DISTRIBUTOR: Sells to retailers. Default price is PTR.
 * - WHOLESALER: Sells in bulk. Default price is PTR.
 */
public enum ShopType {
  RETAILER,
  DISTRIBUTOR,
  WHOLESALER
}
