package com.inventory.pricing.service;

import com.inventory.pricing.domain.model.Pricing;
import com.inventory.pricing.domain.model.Rate;
import com.inventory.pricing.api.ShopRateRule;
import com.inventory.pricing.domain.model.RateRuleSpec;
import com.inventory.pricing.utils.RateRuleTermsConverter;
import com.inventory.pricing.domain.model.Scheme;
import com.inventory.pricing.utils.constants.PricingConstants;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Static factories for rate generation tests. */
final class RateGenerationTestFixtures {

  static final String SHOP = "shop-1";
  static final String OWNER = "owner-1";
  static final String CASHIER = "cashier-1";

  private RateGenerationTestFixtures() {}

  static BigDecimal dec(String value) {
    return value == null ? null : new BigDecimal(value);
  }

  static Scheme tenPlusOne() {
    return new Scheme(PricingConstants.SCHEME_TYPE_FIXED_UNITS, 10, 1, null);
  }

  /** Rate-A = PTR − 8% + 10+1, the spec's worked example. */
  static RateRuleSpec rateA() {
    return new RateRuleSpec("Rate-A", PricingConstants.RATE_BASE_PRICE_TO_RETAIL, dec("8"), tenPlusOne());
  }

  /** Rate-M = MRP − 10%. */
  static RateRuleSpec rateM() {
    return new RateRuleSpec("Rate-M", PricingConstants.RATE_BASE_MAXIMUM_RETAIL_PRICE, dec("10"), null);
  }

  /** A stored pricing record (has an id, so it is an update). */
  static Pricing stored(String id, String ptr, String mrp, String cost, Rate... rates) {
    Pricing p = new Pricing();
    p.setId(id);
    p.setShopId(SHOP);
    p.setPriceToRetail(dec(ptr));
    p.setMaximumRetailPrice(dec(mrp));
    p.setCostPrice(dec(cost));
    p.setDefaultRate(PricingConstants.DEFAULT_RATE_PRICE_TO_RETAIL);
    p.setSellingPrice(dec(ptr));
    p.setRates(new ArrayList<>(Arrays.asList(rates)));
    return p;
  }

  /** A new batch (no id yet). */
  static Pricing fresh(String ptr, String mrp, String cost) {
    Pricing p = stored(null, ptr, mrp, cost);
    p.setRates(null);
    return p;
  }

  static Rate generated(String name, String price, String base, String baseValue, String disc, Scheme scheme,
      boolean manual, String gstMode) {
    return Rate.builder().name(name).price(dec(price)).base(base).baseValue(dec(baseValue))
        .discountPercent(dec(disc)).scheme(scheme).manual(manual).gstMode(gstMode).build();
  }

  /** Rate-A as generated from the rule on PTR 100: 100 × 0.92 × 10/11 = 83.64. */
  static Rate storedRateA100() {
    return generated("Rate-A", "83.64", PricingConstants.RATE_BASE_PRICE_TO_RETAIL, "100", "8", tenPlusOne(),
        false, PricingConstants.GST_MODE_EXCLUSIVE);
  }

  /** Rate-A on PTR 100 overridden to 5%: 86.36, manual. */
  static Rate storedOverrideFivePercent() {
    return generated("Rate-A", "86.36", PricingConstants.RATE_BASE_PRICE_TO_RETAIL, "100", "5", tenPlusOne(),
        true, PricingConstants.GST_MODE_EXCLUSIVE);
  }

  /** The frontend's shape: name and price only. */
  static Rate namePrice(String name, String price) {
    return new Rate(name, dec(price));
  }

  static Rate withDisc(String name, String price, String disc) {
    Rate r = namePrice(name, price);
    r.setDiscountPercent(dec(disc));
    return r;
  }

  static Rate find(List<Rate> rates, String name) {
    return rates.stream().filter(r -> name.equals(r.getName())).findFirst().orElse(null);
  }

  /** The rules as the shop's port hands them to pricing. */
  static List<ShopRateRule> asRules(RateRuleSpec... specs) {
    return java.util.Arrays.stream(specs)
        .map(r -> new ShopRateRule(r.name(), r.base(), r.discountPercent(),
            RateRuleTermsConverter.toTerms(r.scheme())))
        .toList();
  }
}
