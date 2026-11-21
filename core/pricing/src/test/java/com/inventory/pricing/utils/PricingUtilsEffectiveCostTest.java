package com.inventory.pricing.utils;

import com.inventory.pricing.domain.model.Scheme;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PricingUtilsEffectiveCostTest {

  @Test
  void additionalDiscountReducesCost() {
    BigDecimal effective = PricingUtils.computeEffectiveCostPrice(
        new BigDecimal("100"), new BigDecimal("10"), null);
    assertEquals(new BigDecimal("90.0000"), effective);
  }

  @Test
  void fixedUnitsSchemeSpreadsCostOverFreeUnits() {
    Scheme scheme = new Scheme("FIXED_UNITS", 8, 2, null);
    BigDecimal effective = PricingUtils.computeEffectiveCostPrice(
        new BigDecimal("100"), null, scheme);
    assertEquals(new BigDecimal("80.0000"), effective);
  }

  @Test
  void percentageSchemeAndDiscountCompound() {
    Scheme scheme = new Scheme("PERCENTAGE", null, null, new BigDecimal("10"));
    BigDecimal effective = PricingUtils.computeEffectiveCostPrice(
        new BigDecimal("100"), new BigDecimal("10"), scheme);
    assertEquals(new BigDecimal("81.0000"), effective);
  }

  @Test
  void unchangedWhenNothingReducesCost() {
    BigDecimal effective = PricingUtils.computeEffectiveCostPrice(
        new BigDecimal("100"), null, new Scheme("FIXED_UNITS", null, null, null));
    assertEquals(new BigDecimal("100.0000"), effective);
    assertNull(PricingUtils.computeEffectiveCostPrice(null, new BigDecimal("10"), null));
  }

  @Test
  void markupIsSupportedViaNegativeDiscount() {
    BigDecimal effective = PricingUtils.computeEffectiveCostPrice(
        new BigDecimal("100"), new BigDecimal("-5"), null);
    assertEquals(new BigDecimal("105.0000"), effective);
  }

  /**
   * PARAS A00000999: 36 at 99 less a 24% scheme, rates including 5% GST. The bill's taxable value is
   * 2579.66, so the landed cost before GST is 71.6571 a unit, not the 75.24 the scheme alone gives.
   */
  @Test
  void inclusiveCostHasGstTakenOutAfterTheScheme() {
    Scheme scheme = new Scheme("PERCENTAGE", null, null, new BigDecimal("24"));
    BigDecimal effective = PricingUtils.computeEffectiveCostPrice(
        new BigDecimal("99"), null, scheme, true, "2.5", "2.5");
    assertEquals(new BigDecimal("71.6571"), effective);
    assertEquals(new BigDecimal("2579.66"),
        effective.multiply(BigDecimal.valueOf(36)).setScale(2, java.math.RoundingMode.HALF_UP));
  }

  @Test
  void inclusiveCostWithNoRateIsLeftAsLanded() {
    BigDecimal effective = PricingUtils.computeEffectiveCostPrice(
        new BigDecimal("100"), new BigDecimal("10"), null, true, null, "");
    assertEquals(new BigDecimal("90.0000"), effective);
  }

  @Test
  void exclusiveCostIgnoresTheRate() {
    BigDecimal effective = PricingUtils.computeEffectiveCostPrice(
        new BigDecimal("100"), new BigDecimal("10"), null, false, "9", "9");
    assertEquals(new BigDecimal("90.0000"), effective);
  }
}
