package com.inventory.product.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.model.enums.SchemeType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

/** Pins checkout's scheme totals so moving the maths into pricing cannot shift a paisa. */
class CheckoutUtilsSchemeRefactorTest {

  private static PurchaseItem line(String price, String qty) {
    PurchaseItem item = new PurchaseItem();
    item.setPriceToRetail(new BigDecimal(price));
    item.setQuantity(new BigDecimal(qty));
    return item;
  }

  private static BigDecimal lineTotal(PurchaseItem item) {
    return CheckoutUtils.getEffectiveSellingPricePerUnit(item)
        .multiply(CheckoutUtils.getBillableQuantityAsDecimal(item))
        .setScale(2, RoundingMode.HALF_UP);
  }

  @Test
  void fixedUnitsLineTotalUnchanged() {
    PurchaseItem item = line("100", "11");
    item.setSchemeType(SchemeType.FIXED_UNITS);
    item.setSchemePayFor(10);
    item.setSchemeFree(1);

    assertEquals(new BigDecimal("10.0000"), CheckoutUtils.getBillableQuantityAsDecimal(item));
    // FIXED_UNITS cuts quantity, never price.
    assertEquals(new BigDecimal("100"), CheckoutUtils.getEffectiveSellingPricePerUnit(item));
    assertEquals(new BigDecimal("1000.00"), lineTotal(item));
  }

  @Test
  void percentageLineTotalUnchanged() {
    PurchaseItem item = line("100", "10");
    item.setSchemeType(SchemeType.PERCENTAGE);
    item.setSchemePercentage(new BigDecimal("10"));

    // Same scale as today: 100 × 0.9000, unrounded.
    assertEquals(new BigDecimal("100").multiply(new BigDecimal("0.9000")),
        CheckoutUtils.getEffectiveSellingPricePerUnit(item));
    assertEquals(new BigDecimal("10"), CheckoutUtils.getBillableQuantityAsDecimal(item));
    assertEquals(new BigDecimal("900.00"), lineTotal(item));
  }

  @Test
  void noSchemeLeavesPriceAndQuantityAlone() {
    PurchaseItem item = line("92.50", "3");

    assertEquals(new BigDecimal("92.50"), CheckoutUtils.getEffectiveSellingPricePerUnit(item));
    assertEquals(new BigDecimal("3"), CheckoutUtils.getBillableQuantityAsDecimal(item));
  }

  @Test
  void fixedUnitsWithoutPayForBillsTheFullQuantity() {
    PurchaseItem item = line("100", "11");
    item.setSchemeType(SchemeType.FIXED_UNITS);
    item.setSchemeFree(1);

    assertEquals(new BigDecimal("11"), CheckoutUtils.getBillableQuantityAsDecimal(item));
  }

  @Test
  void zeroQuantityBillsNothing() {
    PurchaseItem item = line("100", "0");
    item.setSchemeType(SchemeType.FIXED_UNITS);
    item.setSchemePayFor(10);
    item.setSchemeFree(1);

    assertEquals(BigDecimal.ZERO, CheckoutUtils.getBillableQuantityAsDecimal(item));
  }

  @Test
  void atMrpOnlyWhenRateEqualsMrp() {
    PurchaseItem atMrp = line("115", "1");
    atMrp.setMaximumRetailPrice(new BigDecimal("115.00"));
    PurchaseItem below = line("110", "1");
    below.setMaximumRetailPrice(new BigDecimal("115"));
    PurchaseItem noMrp = line("115", "1");

    assertTrue(CheckoutUtils.isSellingAtMrp(atMrp));
    assertFalse(CheckoutUtils.isSellingAtMrp(below));
    assertFalse(CheckoutUtils.isSellingAtMrp(noMrp));
    assertFalse(CheckoutUtils.isSellingAtMrp(null));
  }
}
