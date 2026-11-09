package com.inventory.product.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A lot whose cost is not below its selling price cannot be sold at a profit. The bill is in the
 * operator's hand at stock-in and nowhere near them when the margin shows up negative in the cart,
 * so the warning has to be raised here.
 */
class CostPriceSanityTest {

  private static BigDecimal bd(String v) {
    return new BigDecimal(v);
  }

  /** The GUMTONE case: a bill discount left off the line, so cost was keyed at the list price. */
  @Test
  void warnsWhenCostEqualsSellingPrice() {
    Optional<String> warning =
        CostPriceSanity.check("GUMTONE POWDER 40GM 1X96", bd("69.33"), bd("69.33"));

    assertTrue(warning.isPresent());
    assertTrue(warning.get().contains("GUMTONE POWDER 40GM 1X96"), warning.get());
    assertTrue(warning.get().contains("69.33"), warning.get());
  }

  @Test
  void warnsWhenCostIsAboveSellingPrice() {
    assertTrue(CostPriceSanity.check("LIV 52", bd("80.00"), bd("69.33")).isPresent());
  }

  @Test
  void staysSilentWhenCostIsBelowSellingPrice() {
    assertFalse(CostPriceSanity.check("LIV 52", bd("61.15"), bd("69.33")).isPresent());
  }

  /** Scale must not decide the answer: 69.33 and 69.330 are the same price. */
  @Test
  void treatsTheSamePriceWrittenTwoWaysAsEqual() {
    assertTrue(CostPriceSanity.check("LIV 52", bd("69.330"), bd("69.33")).isPresent());
  }

  /** Nothing to compare is not a finding — an unpriced line is a different problem. */
  @Test
  void staysSilentWhenEitherPriceIsMissing() {
    assertFalse(CostPriceSanity.check("LIV 52", null, bd("69.33")).isPresent());
    assertFalse(CostPriceSanity.check("LIV 52", bd("61.15"), null).isPresent());
    assertFalse(CostPriceSanity.check("LIV 52", bd("61.15"), BigDecimal.ZERO).isPresent());
  }

  @Test
  void namesTheLineEvenWhenTheProductHasNoName() {
    Optional<String> warning = CostPriceSanity.check(null, bd("69.33"), bd("69.33"));
    assertTrue(warning.isPresent());
    assertEquals(true, warning.get().toLowerCase().contains("cost"), warning.get());
  }
}
