package com.inventory.taxation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HsnSacCatalogTest {

  private final HsnSacCatalog catalog = new HsnSacCatalog(Map.of(
      "3004", "MEDICAMENTS …",
      "300490", "OTHER :",
      "30049011", "OF AYURVEDIC SYSTEM",
      "99", "All Services"));

  @Test
  void exactEightDigitMatch() {
    assertEquals("OF AYURVEDIC SYSTEM", catalog.descriptionFor("30049011").orElseThrow());
  }

  @Test
  void fallsBackToSixThenFour() {
    assertEquals("OTHER :", catalog.descriptionFor("30049099").orElseThrow());
    HsnSacCatalog headingOnly = new HsnSacCatalog(Map.of("3004", "MEDICAMENTS …"));
    assertEquals("MEDICAMENTS …", headingOnly.descriptionFor("30049011").orElseThrow());
  }

  @Test
  void ignoresPunctuationAndBlankUnknown() {
    assertEquals("OF AYURVEDIC SYSTEM", catalog.descriptionFor("3004 9011").orElseThrow());
    assertEquals(Optional.empty(), catalog.descriptionFor("0"));
    assertEquals(Optional.empty(), catalog.descriptionFor(""));
    assertEquals(Optional.empty(), catalog.descriptionFor("88888888"));
  }
}
