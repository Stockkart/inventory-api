package com.inventory.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

/**
 * The identity customer purchase history is grouped by.
 *
 * <p>Exercised through reflection because the key is an implementation detail of
 * the service rather than part of its API, and the behaviour worth pinning is
 * which things collapse to one key and which stay apart.
 */
class CustomerProductHistoryKeyTest {

  private static String key(String name, String company) throws Exception {
    Method method = CustomerProductHistoryService.class
        .getDeclaredMethod("productKey", String.class, String.class);
    method.setAccessible(true);
    return (String) method.invoke(null, name, company);
  }

  @Test
  void theSameMedicineFromTheSameCompanyIsOneKey() throws Exception {
    // Two deliveries of the same product differ only by lot, which does not
    // appear here at all -- that is the point of the change.
    assertEquals(key("LIV. 52 SYRUP 200ML", "HIMALAYA"),
        key("LIV. 52 SYRUP 200ML", "HIMALAYA"));
  }

  @Test
  void caseAndSurroundingSpaceDoNotSplitAKey() throws Exception {
    assertEquals(key("Liv. 52 Syrup 200ml", "Himalaya"),
        key("  LIV. 52 SYRUP 200ML  ", " HIMALAYA "));
  }

  @Test
  void unitDoesNotEnterTheKey() throws Exception {
    // A legacy import can record one medicine under two units -- PCS and PH, the
    // old system's catch-all for pieces. The application's product identity
    // includes the unit and so forks them into two products; history must still
    // treat them as one thing, because the customer bought one thing.
    String pcs = key("ABANA TABLETS 60'S 1X100", "HIMALAYA WELLNESS COMPANY - ZEAL");
    String ph = key("ABANA TABLETS 60'S 1X100", "HIMALAYA WELLNESS COMPANY - ZEAL");
    assertEquals(pcs, ph);
  }

  @Test
  void differentMedicinesAreDifferentKeys() throws Exception {
    // Both are HSN 30049011, along with 282 other products in one real shop.
    // Keying on HSN would make these the same; keying on the product does not.
    assertNotEquals(key("ABANA TABLETS 60'S 1X100", "HIMALAYA"),
        key("SHIGRU TABLET 60'S 1X60", "HIMALAYA"));
  }

  @Test
  void theSameNameFromDifferentCompaniesStaysApart() throws Exception {
    assertNotEquals(key("PARACETAMOL 500MG 1X10", "CIPLA"),
        key("PARACETAMOL 500MG 1X10", "SUN PHARMA"));
  }

  @Test
  void aMissingCompanyStillProducesAUsableKey() throws Exception {
    assertEquals(key("SOME ITEM", null), key("SOME ITEM", ""));
  }
}
