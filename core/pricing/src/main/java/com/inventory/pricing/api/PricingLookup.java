package com.inventory.pricing.api;

import java.util.List;
import java.util.Optional;

/** Reads stored pricing records. Other modules never read the {@code pricing} collection. */
public interface PricingLookup {

  /** @return the record, or empty when there is none with this id */
  Optional<PricingView> findById(String pricingId);

  /** @return the records that exist among these ids, in no particular order */
  List<PricingView> findAllById(Iterable<String> pricingIds);

  /**
   * @return the distinct rate names used on the shop's pricing records, sorted ignoring case,
   *     blank names left out
   */
  List<String> rateNames(String shopId);
}
