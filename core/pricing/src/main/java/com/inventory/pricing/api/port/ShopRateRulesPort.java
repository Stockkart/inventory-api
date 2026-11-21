package com.inventory.pricing.api.port;

import com.inventory.pricing.api.ShopRateRule;
import java.util.List;

/**
 * Reads a shop's rate rules and the grant to edit generated rates. Declared here because pricing
 * cannot read the product-module settings (product → pricing only); implemented in the product
 * module.
 */
public interface ShopRateRulesPort {

  /** The shop's rate rules in display order; empty when the shop has none. */
  List<ShopRateRule> rulesFor(String shopId);

  /** True when the user may override or reset a generated rate's terms in this shop. */
  boolean canEditRates(String shopId, String userId);
}
