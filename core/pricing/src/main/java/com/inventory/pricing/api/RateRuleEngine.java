package com.inventory.pricing.api;

import java.util.List;

/** Rate rule maths for other modules: what rules generate, and whether a rule's terms are valid. */
public interface RateRuleEngine {

  /** What {@code rules} generate for a batch with {@code prices}, in rule order. */
  GeneratedRates generate(List<ShopRateRule> rules, BatchPrices prices);

  /**
   * Checks a rule's scheme as a rate's terms are checked on save.
   *
   * @throws com.inventory.common.exception.ValidationException when the scheme is incomplete or
   *     out of range
   */
  void validateScheme(String ruleName, SchemeTerms scheme);
}
