package com.inventory.pricing.utils;

import static com.inventory.pricing.utils.RateRuleTestFixtures.fixedUnits;
import static com.inventory.pricing.utils.RateRuleTestFixtures.percentage;
import static com.inventory.pricing.utils.RateRuleTestFixtures.rule;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.pricing.domain.model.Rate;
import com.inventory.pricing.domain.model.RateGenerationResult;
import com.inventory.pricing.domain.model.RateRuleSpec;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class RateRuleCalculatorTest {

  private static final String SP = "sellingPrice";
  private static final String PTR = "priceToRetail";
  private static final String MRP = "maximumRetailPrice";
  private static final BigDecimal GST = new BigDecimal("12");

  private static BigDecimal bd(String v) {
    return v == null ? null : new BigDecimal(v);
  }

  private static RateGenerationResult gen(List<RateRuleSpec> rules, String sp, String ptr, String mrp,
      String cost) {
    return RateRuleCalculator.generate(rules, bd(sp), bd(ptr), bd(mrp), bd(cost), GST);
  }

  private static Rate only(RateGenerationResult r) {
    assertEquals(1, r.rates().size());
    return r.rates().get(0);
  }

  @Test
  void discAndSchemeOnPtr() {
    Rate rate = only(gen(List.of(rule("Rate-A", PTR, "8", fixedUnits(10, 1))), null, "100", "150", null));
    assertEquals(new BigDecimal("83.64"), rate.getPrice());
    assertEquals(0, new BigDecimal("100").compareTo(rate.getBaseValue()));
    assertEquals("EXCLUSIVE", rate.getGstMode());
    assertEquals("Rate-A", rate.getName());
    assertEquals(PTR, rate.getBase());
    assertFalse(rate.getManual());
  }

  @Test
  void discOnlyOnPtr() {
    Rate rate = only(gen(List.of(rule("B", PTR, "7", null)), null, "100", "150", null));
    assertEquals(new BigDecimal("93.00"), rate.getPrice());
  }

  @Test
  void mrpBaseIsInclusive() {
    Rate rate = only(gen(List.of(rule("C", MRP, "25", null)), null, "100", "150", null));
    assertEquals(new BigDecimal("112.50"), rate.getPrice());
    assertEquals("INCLUSIVE", rate.getGstMode());
  }

  @Test
  void ptrEqualsMrpIsInclusive() {
    Rate rate = only(gen(List.of(rule("D", PTR, "8", null)), null, "100", "100", null));
    assertEquals(new BigDecimal("92.00"), rate.getPrice());
    assertEquals("INCLUSIVE", rate.getGstMode());
  }

  @Test
  void retailerSellingPriceBaseIsInclusive() {
    Rate rate = only(gen(List.of(rule("E", SP, "5", null)), "150", null, "150", null));
    assertEquals(new BigDecimal("142.50"), rate.getPrice());
    assertEquals("INCLUSIVE", rate.getGstMode());
  }

  @Test
  void percentageScheme() {
    Rate rate = only(gen(List.of(rule("F", PTR, "0", percentage("10"))), null, "100", "150", null));
    assertEquals(new BigDecimal("90.00"), rate.getPrice());
  }

  @Test
  void noDiscountLeavesBaseUnchanged() {
    Rate rate = only(gen(List.of(rule("G", PTR, null, null)), null, "100", "150", null));
    assertEquals(new BigDecimal("100.00"), rate.getPrice());
  }

  @Test
  void belowCostExclusiveSkipped() {
    RateGenerationResult r = gen(List.of(rule("Low", PTR, "25", null), rule("Ok", PTR, "5", null)),
        null, "100", "150", "80");
    assertEquals(1, r.rates().size());
    assertEquals("Ok", r.rates().get(0).getName());
    assertEquals(1, r.skipped().size());
    assertEquals("Low", r.skipped().get(0).name());
    assertEquals("BELOW_COST", r.skipped().get(0).reason());
  }

  @Test
  void belowCostInclusiveComparedExGst() {
    List<RateRuleSpec> rules = List.of(rule("M", MRP, "10", null));
    RateGenerationResult skipped = gen(rules, null, null, "100", "81");
    assertTrue(skipped.rates().isEmpty());
    assertEquals("BELOW_COST", skipped.skipped().get(0).reason());
    RateGenerationResult kept = gen(rules, null, null, "100", "80");
    assertEquals(new BigDecimal("90.00"), only(kept).getPrice());
    assertTrue(kept.skipped().isEmpty());
  }

  @Test
  void noCostNeverSkips() {
    RateGenerationResult r = gen(List.of(rule("Low", PTR, "90", null)), null, "100", "150", null);
    assertEquals(1, r.rates().size());
    assertTrue(r.skipped().isEmpty());
  }

  @Test
  void missingBaseValueSkipsRuleQuietly() {
    RateGenerationResult r = gen(List.of(rule("NoPtr", PTR, "5", null), rule("Ok", MRP, "5", null)),
        null, null, "150", null);
    assertEquals(1, r.rates().size());
    assertEquals("Ok", r.rates().get(0).getName());
    assertTrue(r.skipped().isEmpty());
  }
}
