package com.inventory.pricing.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SchemePriceUtilsTest {

  @Test
  void percentagePriceMatchesCheckout() {
    assertEquals(new BigDecimal("92").multiply(new BigDecimal("0.900000")),
        SchemePriceUtils.percentagePrice(new BigDecimal("92"), "PERCENTAGE", new BigDecimal("10")));
  }

  /** The percentage is divided by 100 at 6 dp: 12.345% is 0.12345, not 0.1235. */
  @Test
  void percentagePriceDividesThePercentageAtSixPlaces() {
    assertEquals(0, new BigDecimal("87.655").compareTo(
        SchemePriceUtils.percentagePrice(new BigDecimal("100"), "PERCENTAGE", new BigDecimal("12.345"))));
  }

  @Test
  void percentagePriceIgnoresFixedUnits() {
    assertEquals(new BigDecimal("92"),
        SchemePriceUtils.percentagePrice(new BigDecimal("92"), "FIXED_UNITS", new BigDecimal("10")));
  }

  @Test
  void percentagePriceLeavesPriceAloneWithoutAPositivePercentage() {
    assertEquals(new BigDecimal("92"),
        SchemePriceUtils.percentagePrice(new BigDecimal("92"), "PERCENTAGE", null));
    assertEquals(new BigDecimal("92"),
        SchemePriceUtils.percentagePrice(new BigDecimal("92"), "PERCENTAGE", BigDecimal.ZERO));
    assertEquals(new BigDecimal("92"),
        SchemePriceUtils.percentagePrice(new BigDecimal("92"), null, new BigDecimal("10")));
  }

  @Test
  void billableQuantityTenPlusOne() {
    assertEquals(new BigDecimal("10.0000"),
        SchemePriceUtils.billableQuantity(new BigDecimal("11"), "FIXED_UNITS", 10, 1));
  }

  @Test
  void billableQuantityPercentageUnchanged() {
    assertEquals(new BigDecimal("11"),
        SchemePriceUtils.billableQuantity(new BigDecimal("11"), "PERCENTAGE", 10, 1));
  }

  @Test
  void billableQuantityWithoutAUsableDealIsTheFullQuantity() {
    assertEquals(new BigDecimal("11"),
        SchemePriceUtils.billableQuantity(new BigDecimal("11"), "FIXED_UNITS", null, 1));
    assertEquals(new BigDecimal("11"),
        SchemePriceUtils.billableQuantity(new BigDecimal("11"), "FIXED_UNITS", 0, 1));
    assertEquals(new BigDecimal("11"),
        SchemePriceUtils.billableQuantity(new BigDecimal("11"), "FIXED_UNITS", 10, null));
    assertEquals(new BigDecimal("11"),
        SchemePriceUtils.billableQuantity(new BigDecimal("11"), null, 10, 1));
  }

  @Test
  void isAtMrpTrueOnlyWhenEqual() {
    assertTrue(SchemePriceUtils.isAtMrp(new BigDecimal("115"), new BigDecimal("115.00")));
    assertFalse(SchemePriceUtils.isAtMrp(new BigDecimal("110"), new BigDecimal("115")));
    assertFalse(SchemePriceUtils.isAtMrp(null, new BigDecimal("115")));
    assertFalse(SchemePriceUtils.isAtMrp(new BigDecimal("115"), null));
  }
}
