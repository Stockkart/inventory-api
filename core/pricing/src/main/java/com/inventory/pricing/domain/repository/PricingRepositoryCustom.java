package com.inventory.pricing.domain.repository;

import java.util.List;

/**
 * Custom repository fragment for queries Spring Data Mongo cannot derive from method names.
 * Implemented by {@link PricingRepositoryImpl}, which Spring Data picks up by naming convention.
 */
public interface PricingRepositoryCustom {

  /**
   * Returns the distinct named-rate names ({@code rates.name}) used across all pricing documents
   * of the given shop, sorted case-insensitively for a stable order. Null and blank names are
   * excluded.
   */
  List<String> findDistinctRateNamesByShopId(String shopId);
}
