package com.inventory.pricing.api;

import com.inventory.pricing.api.dto.PricingCreateCommand;
import com.inventory.pricing.api.dto.PricingReadDto;
import com.inventory.pricing.api.dto.PricingUpdateCommand;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Port for inventory pricing operations.
 * Product module depends on this interface only, not on pricing internals.
 */
public interface InventoryPricingAdapter {

  /** Find pricing by ID. Returns empty if not found. */
  Optional<PricingReadDto> findById(String pricingId);

  /** Find pricing by IDs. Returns map of pricingId -> PricingReadDto. */
  Map<String, PricingReadDto> findByIdIn(List<String> pricingIds);

  /** Create pricing and return the new pricing ID. */
  String create(PricingCreateCommand command);

  /** Update pricing by ID. */
  void update(String pricingId, PricingUpdateCommand command);
}
