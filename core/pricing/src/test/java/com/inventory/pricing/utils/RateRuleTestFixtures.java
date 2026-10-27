package com.inventory.pricing.utils;

import com.inventory.pricing.domain.model.RateRuleSpec;
import com.inventory.pricing.domain.model.Scheme;
import com.inventory.pricing.utils.constants.PricingConstants;

import java.math.BigDecimal;

/** Static factories for rate rule tests. */
final class RateRuleTestFixtures {

  private RateRuleTestFixtures() {}

  static RateRuleSpec rule(String name, String base, String discountPercent, Scheme scheme) {
    return new RateRuleSpec(name, base,
        discountPercent == null ? null : new BigDecimal(discountPercent), scheme);
  }

  static Scheme fixedUnits(int payFor, int free) {
    return new Scheme(PricingConstants.SCHEME_TYPE_FIXED_UNITS, payFor, free, null);
  }

  static Scheme percentage(String percentage) {
    return new Scheme(PricingConstants.SCHEME_TYPE_PERCENTAGE, null, null, new BigDecimal(percentage));
  }
}
