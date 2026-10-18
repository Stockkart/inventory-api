package com.inventory.pricing.service;

import com.inventory.pricing.domain.model.RateRuleSpec;

import java.util.List;

/**
 * Reads a shop's rate rules and the owner grant to edit generated rates. Declared here because
 * pricing cannot read the product-module settings (product → pricing only, L1); implemented in the
 * product module (L2). Injected {@code @Autowired(required = false)}: absent means no rules.
 */
public interface ShopRateRulesPort {

  /** The shop's rate rules in display order; empty when the shop has none. */
  List<RateRuleSpec> rulesFor(String shopId);

  /** True when the shop is a RETAILER. */
  boolean isRetailer(String shopId);

  /** True when the user may override or reset a generated rate's terms in this shop. */
  boolean canEditRates(String userId, String shopId);
}
