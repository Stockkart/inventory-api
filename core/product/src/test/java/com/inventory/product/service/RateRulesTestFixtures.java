package com.inventory.product.service;

import com.inventory.pricing.rest.dto.response.SchemeDto;
import com.inventory.pricing.utils.constants.PricingConstants;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.model.enums.ShopType;
import com.inventory.product.rest.dto.response.RateRuleDto;
import java.math.BigDecimal;

/** Static factories for rate rules tests. */
public final class RateRulesTestFixtures {

  public static final String SHOP = "shop-1";
  public static final String OWNER = "owner-1";
  public static final String CASHIER = "cashier-1";

  private RateRulesTestFixtures() {}

  public static BigDecimal dec(String value) {
    return value == null ? null : new BigDecimal(value);
  }

  public static SchemeDto tenPlusOne() {
    return new SchemeDto(PricingConstants.SCHEME_TYPE_FIXED_UNITS, 10, 1, null);
  }

  public static RateRuleDto rule(String name, String base, String disc, SchemeDto scheme) {
    return new RateRuleDto(name, base, dec(disc), scheme);
  }

  /** Rate-A = PTR - 8% + 10+1, the spec's worked example. */
  public static RateRuleDto rateA() {
    return rule("Rate-A", PricingConstants.RATE_BASE_PRICE_TO_RETAIL, "8", tenPlusOne());
  }

  /** Rate-S = selling price - 5%, the retailer rule. */
  public static RateRuleDto rateS() {
    return rule("Rate-S", PricingConstants.RATE_BASE_SELLING_PRICE, "5", null);
  }

  public static Shop shop(ShopType type, String verticalId) {
    Shop shop = new Shop();
    shop.setShopId(SHOP);
    shop.setShopType(type);
    shop.setVerticalId(verticalId);
    return shop;
  }
}
