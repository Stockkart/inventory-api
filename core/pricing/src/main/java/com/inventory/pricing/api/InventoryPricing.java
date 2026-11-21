package com.inventory.pricing.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Reads and writes the pricing of inventory lots. */
public interface InventoryPricing {

  /** @return the lot pricing, or empty when there is none with this id */
  Optional<LotPricing> findById(String pricingId);

  /** @return pricing id to lot pricing, for the ids that exist */
  Map<String, LotPricing> findByIdIn(List<String> pricingIds);

  /** Creates a lot's pricing. @return the new pricing id */
  String create(PricingCreate command);

  /**
   * Updates a lot's pricing. {@code userId} is checked for the grant to edit generated rates.
   */
  void update(String pricingId, PricingUpdate command, String userId);
}
