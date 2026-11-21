package com.inventory.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class GstMathTest {

  private static BigDecimal bd(String value) {
    return new BigDecimal(value);
  }

  private static void assertMoney(String expected, BigDecimal actual) {
    assertEquals(0, bd(expected).compareTo(actual), "expected " + expected + " but was " + actual);
  }

  @Test
  void parsesStoredRatesInEveryShapeTheyArriveIn() {
    assertMoney("9", GstMath.parseGstRate("9"));
    assertMoney("9", GstMath.parseGstRate("9%"));
    assertMoney("2.5", GstMath.parseGstRate(" 2.5 % "));
    assertMoney("0", GstMath.parseGstRate(null));
    assertMoney("0", GstMath.parseGstRate(""));
    assertMoney("0", GstMath.parseGstRate("nine"));
    assertMoney("0", GstMath.parseGstRate("-5"));
  }

  @Test
  void knowsTheGstSlabs() {
    assertTrue(GstMath.isKnownSlab(bd("5.00")));
    assertTrue(GstMath.isKnownSlab(bd("0.25")));
    assertFalse(GstMath.isKnownSlab(bd("7")));
    assertFalse(GstMath.isKnownSlab(null));
  }

  @Test
  void addsTaxOnAnExclusiveValueAtTwoDecimalsHalfUp() {
    assertMoney("12.35", GstMath.taxOnExclusive(bd("247.00"), bd("5")));
    assertMoney("0.01", GstMath.taxOnExclusive(bd("0.10"), bd("5")));
    assertMoney("0", GstMath.taxOnExclusive(bd("100"), BigDecimal.ZERO));
    assertMoney("0", GstMath.taxOnExclusive(null, bd("5")));
  }

  @Test
  void extractsTaxFromAnInclusiveAmountSoTheTwoAddBack() {
    GstMath.TaxSplit split = GstMath.extractFromInclusive(bd("105.00"), bd("5"));
    assertMoney("100.00", split.taxable());
    assertMoney("5.00", split.tax());

    GstMath.TaxSplit odd = GstMath.extractFromInclusive(bd("99.99"), bd("12"));
    assertMoney("89.28", odd.taxable());
    assertMoney("99.99", odd.taxable().add(odd.tax()));

    GstMath.TaxSplit untaxed = GstMath.extractFromInclusive(bd("50"), BigDecimal.ZERO);
    assertMoney("50.00", untaxed.taxable());
    assertMoney("0", untaxed.tax());
  }

  @Test
  void splitsIntraStateTaxIntoEqualHalvesFromHalfTheRate() {
    GstMath.IntraStateTax halves = GstMath.splitIntraState(bd("1000.00"), bd("5"));
    assertMoney("25.00", halves.centralTax());
    assertMoney("25.00", halves.stateTax());
    assertMoney("50.00", halves.total());

    // 0.25% does not halve at the money scale; the half-rate is carried at 4dp.
    GstMath.IntraStateTax quarter = GstMath.splitIntraState(bd("10000.00"), bd("0.25"));
    assertMoney("12.50", quarter.centralTax());
    assertEquals(quarter.centralTax(), quarter.stateTax());
  }
}
