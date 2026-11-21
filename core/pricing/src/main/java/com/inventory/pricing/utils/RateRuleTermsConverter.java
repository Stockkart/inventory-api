package com.inventory.pricing.utils;

import com.inventory.pricing.api.SchemeTerms;
import com.inventory.pricing.api.ShopRateRule;
import com.inventory.pricing.domain.model.RateRuleSpec;
import com.inventory.pricing.domain.model.Scheme;

/** Converts rate rule terms between the pricing api records and pricing's own model. */
public final class RateRuleTermsConverter {

  private RateRuleTermsConverter() {}

  public static RateRuleSpec toSpec(ShopRateRule rule) {
    return new RateRuleSpec(rule.name(), rule.base(), rule.discountPercent(), toScheme(rule.scheme()));
  }

  public static Scheme toScheme(SchemeTerms terms) {
    if (terms == null) {
      return null;
    }
    return new Scheme(terms.schemeType(), terms.schemePayFor(), terms.schemeFree(),
        terms.schemePercentage());
  }

  public static SchemeTerms toTerms(Scheme scheme) {
    if (scheme == null) {
      return null;
    }
    return new SchemeTerms(scheme.getSchemeType(), scheme.getSchemePayFor(), scheme.getSchemeFree(),
        scheme.getSchemePercentage());
  }
}
