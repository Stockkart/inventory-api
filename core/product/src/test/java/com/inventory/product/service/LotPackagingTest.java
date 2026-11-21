package com.inventory.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.product.domain.model.UnitConversion;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class LotPackagingTest {

  @Test
  void factorDefaultsToOneWithoutConversion() {
    assertEquals(1, LotPackaging.factor(null));
    assertEquals(1, LotPackaging.factor(new UnitConversion("PAC", 0)));
    assertEquals(60, LotPackaging.factor(new UnitConversion("PAC", 60)));
  }

  @Test
  void lotRegisteredWithoutFactorUnderSixtyPerPackProductIsDrifted() {
    // 10 packs stored as 10 base units, product says 1 × 60 PAC.
    assertTrue(LotPackaging.isBaseCountDrifted(10, new BigDecimal("10.0000"), 60));
    assertEquals(600, LotPackaging.toBase(new BigDecimal("10.0000"), 60));
  }

  @Test
  void lotRegisteredAtNinetyPerPackUnderBottleProductIsDrifted() {
    // 20 packs stored as 1800 base units, product has no pack conversion.
    assertTrue(LotPackaging.isBaseCountDrifted(1800, new BigDecimal("20.0000"), 1));
    assertEquals(20, LotPackaging.toBase(new BigDecimal("20.0000"), 1));
  }

  @Test
  void fractionalSaleRoundingIsNotDrift() {
    // 600 base minus 61 single-capsule sales, display rounded to 4dp on each sale.
    BigDecimal display = new BigDecimal("10.0000");
    BigDecimal oneCapsule = new BigDecimal("0.0167");
    for (int i = 0; i < 61; i++) {
      display = display.subtract(oneCapsule);
    }
    assertFalse(LotPackaging.isBaseCountDrifted(539, display, 60));
  }

  @Test
  void consistentOrLegacyCountsAreNotDrift() {
    assertFalse(LotPackaging.isBaseCountDrifted(600, new BigDecimal("10.0000"), 60));
    assertFalse(LotPackaging.isBaseCountDrifted(20, new BigDecimal("20.0000"), 1));
    assertFalse(LotPackaging.isBaseCountDrifted(null, new BigDecimal("20.0000"), 1));
    assertFalse(LotPackaging.isBaseCountDrifted(20, null, 1));
  }

  @Test
  void describeShowsPackOrBaseUnit() {
    assertEquals("1 × 60 PAC", LotPackaging.describe("PCS", new UnitConversion("PAC", 60)));
    assertEquals("BTL", LotPackaging.describe("BTL", null));
  }
}
