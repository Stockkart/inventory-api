package com.inventory.user.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DrugLicenceTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);

  @Test
  void aNumberWithoutAnExpiryIsMissingIt() {
    assertTrue(DrugLicence.missingExpiry("20B/123", null));
  }

  @Test
  void noNumberNeedsNoExpiry() {
    assertFalse(DrugLicence.missingExpiry(null, null));
    assertFalse(DrugLicence.missingExpiry("  ", null));
  }

  @Test
  void aNumberWithAnExpiryIsComplete() {
    assertFalse(DrugLicence.missingExpiry("20B/123", TODAY));
  }

  @Test
  void anExpiryIsDroppedWhenThereIsNoNumber() {
    assertNull(DrugLicence.expiryToStore(null, TODAY));
    assertNull(DrugLicence.expiryToStore("", TODAY));
    assertEquals(TODAY, DrugLicence.expiryToStore("20B/123", TODAY));
  }

  @Test
  void theLicenceIsStillValidOnItsExpiryDate() {
    assertFalse(DrugLicence.isExpired(TODAY, TODAY));
  }

  @Test
  void theLicenceHasExpiredTheDayAfter() {
    assertTrue(DrugLicence.isExpired(TODAY.minusDays(1), TODAY));
  }

  @Test
  void noExpiryDateMeansNoAnswer() {
    assertNull(DrugLicence.isExpired(null, TODAY));
  }
}
