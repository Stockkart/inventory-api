package com.inventory.hr.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MaskingTest {

  @Test
  void keepsTheLastFourAndHidesTheRest() {
    assertEquals("••••••••1234", Masking.maskLast4("123456781234"));
    assertEquals("••••••234F", Masking.maskLast4("ABCDE1234F"));
    assertEquals("•2345", Masking.maskLast4("12345"));
  }

  @Test
  void maskedValueHasTheSameLengthAndNoCharacterOfTheHiddenPart() {
    String aadhaar = "999988887777";
    String masked = Masking.maskLast4(aadhaar);
    assertEquals(aadhaar.length(), masked.length());
    for (int i = 0; i < masked.length() - 4; i++) {
      assertEquals(Masking.BULLET, masked.charAt(i), "position " + i);
    }
    assertTrue(masked.endsWith("7777"));
    assertFalse(masked.contains(aadhaar));
  }

  @Test
  void doesNotTrimSoTheLengthRuleHoldsExactly() {
    assertEquals("••••••34F ", Masking.maskLast4("ABCD1234F "));
    assertEquals(" ABCD1234F".length(), Masking.maskLast4(" ABCD1234F").length());
  }

  @Test
  void shortValuesAreHiddenWholeInsteadOfShown() {
    assertEquals(Masking.ALL_HIDDEN, Masking.maskLast4("1234"));
    assertEquals(Masking.ALL_HIDDEN, Masking.maskLast4("12"));
    assertEquals(Masking.ALL_HIDDEN, Masking.maskLast4(""));
    assertEquals("••••", Masking.ALL_HIDDEN);
  }

  @Test
  void nullStaysNull() {
    assertNull(Masking.maskLast4(null));
  }
}
