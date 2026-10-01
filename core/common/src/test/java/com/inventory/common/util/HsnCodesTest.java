package com.inventory.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HsnCodesTest {

  private final Map<String, String> table =
      Map.of("30", "chapter", "3004", "heading", "300490", "subheading");

  @Test
  void keepsOnlyTheDigits() {
    assertEquals("30049099", HsnCodes.digitsOnly(" 3004.90-99 "));
    assertEquals("", HsnCodes.digitsOnly(null));
  }

  @Test
  void fallsBackToTheNearestParent() {
    assertEquals(Optional.of("subheading"), HsnCodes.mostSpecific("30049099", table::get, 2));
    assertEquals(Optional.of("heading"), HsnCodes.mostSpecific("30041000", table::get, 2));
    assertEquals(Optional.of("chapter"), HsnCodes.mostSpecific("30111111", table::get, 2));
  }

  @Test
  void stopsAtTheShortestPrefixAllowed() {
    assertEquals(Optional.empty(), HsnCodes.mostSpecific("30111111", table::get, 4));
  }

  @Test
  void aBlankOrZeroCodeMatchesNothing() {
    assertEquals(Optional.empty(), HsnCodes.mostSpecific("", table::get, 2));
    assertEquals(Optional.empty(), HsnCodes.mostSpecific("0000", table::get, 2));
  }
}
