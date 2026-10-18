package com.inventory.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.common.exception.ValidationException;
import com.inventory.pricing.rest.dto.response.RateDto;
import com.inventory.pricing.rest.dto.response.SchemeDto;
import com.inventory.pricing.utils.constants.PricingConstants;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.service.CartRateResolver.LineTerms;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CartRateResolverTest {

  private final CartRateResolver resolver = new CartRateResolver();

  @Test
  void ruleRatePutsTheBaseOnTheLineWithItsTerms() {
    LineTerms terms = resolver.resolve(batch(null, rateA(), legacy()), "Rate-A").orElseThrow();

    assertEquals(0, new BigDecimal("100").compareTo(terms.price()));
    assertEquals(0, new BigDecimal("8").compareTo(terms.discountPercent()));
    assertEquals(PricingConstants.SCHEME_TYPE_FIXED_UNITS, terms.schemeType());
    assertEquals(10, terms.payFor());
    assertEquals(1, terms.free());
    assertNull(terms.percentage());
    assertTrue(terms.replacesTerms());
  }

  @Test
  void ruleRateWithoutDiscountOrSchemeStatesZeroAndNone() {
    RateDto schemeOnly = rule("Rate-C", "100", null, null);

    LineTerms terms = resolver.resolve(batch(null, schemeOnly), "Rate-C").orElseThrow();

    assertEquals(0, BigDecimal.ZERO.compareTo(terms.discountPercent()));
    assertNull(terms.schemeType());
    assertNull(terms.payFor());
    assertNull(terms.free());
    assertNull(terms.percentage());
  }

  @Test
  void percentageSchemeCarriesOnlyItsPercentage() {
    RateDto pct = rule("Rate-P", "100", "2",
        new SchemeDto(PricingConstants.SCHEME_TYPE_PERCENTAGE, 10, 1, new BigDecimal("5")));

    LineTerms terms = resolver.resolve(batch(null, pct), "Rate-P").orElseThrow();

    assertEquals(PricingConstants.SCHEME_TYPE_PERCENTAGE, terms.schemeType());
    assertNull(terms.payFor());
    assertNull(terms.free());
    assertEquals(0, new BigDecimal("5").compareTo(terms.percentage()));
  }

  @Test
  void legacyRateGivesItsPriceAndLeavesTermsAlone() {
    LineTerms terms = resolver.resolve(batch(null, rateA(), legacy()), "Old").orElseThrow();

    assertEquals(0, new BigDecimal("95").compareTo(terms.price()));
    assertNull(terms.discountPercent());
    assertNull(terms.schemeType());
    assertTrue(!terms.replacesTerms());
  }

  @Test
  void noRateNameUsesADefaultRuleRate() {
    LineTerms terms = resolver.resolve(batch("Rate-A", rateA(), legacy()), null).orElseThrow();

    assertEquals(0, new BigDecimal("100").compareTo(terms.price()));
    assertEquals(0, new BigDecimal("8").compareTo(terms.discountPercent()));
  }

  @Test
  void noRateNameIgnoresALegacyOrSystemDefault() {
    assertEquals(Optional.empty(), resolver.resolve(batch("Old", rateA(), legacy()), null));
    assertEquals(Optional.empty(), resolver.resolve(batch("priceToRetail", rateA()), null));
    assertEquals(Optional.empty(), resolver.resolve(batch(null, rateA()), null));
    assertEquals(Optional.empty(), resolver.resolve(batch("Rate-A"), null));
  }

  @Test
  void blankRateNameIsTreatedAsNone() {
    assertEquals(Optional.empty(), resolver.resolve(batch(null, rateA()), "  "));
  }

  @Test
  void unknownRateNameRejected() {
    ValidationException e = assertThrows(ValidationException.class,
        () -> resolver.resolve(batch(null, rateA()), "Rate-Z"));
    assertTrue(e.getMessage().contains("Unknown rate: Rate-Z"), e.getMessage());
    assertThrows(ValidationException.class, () -> resolver.resolve(batch(null), "Rate-A"));
  }

  static RateDto rateA() {
    return rule("Rate-A", "100", "8",
        new SchemeDto(PricingConstants.SCHEME_TYPE_FIXED_UNITS, 10, 1, null));
  }

  static RateDto rule(String name, String baseValue, String disc, SchemeDto scheme) {
    RateDto rate = new RateDto(name, new BigDecimal("83.64"));
    rate.setBase(PricingConstants.RATE_BASE_PRICE_TO_RETAIL);
    rate.setBaseValue(new BigDecimal(baseValue));
    rate.setDiscountPercent(disc != null ? new BigDecimal(disc) : null);
    rate.setScheme(scheme);
    return rate;
  }

  static RateDto legacy() {
    return new RateDto("Old", new BigDecimal("95"));
  }

  private static Inventory batch(String defaultRate, RateDto... rates) {
    Inventory inventory = new Inventory();
    inventory.setId("lot-1");
    inventory.setDefaultRate(defaultRate);
    inventory.setRates(List.of(rates));
    return inventory;
  }
}
