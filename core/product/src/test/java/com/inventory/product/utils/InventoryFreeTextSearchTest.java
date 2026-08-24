package com.inventory.product.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class InventoryFreeTextSearchTest {

  @Test
  void identifierTokens_keepsFullPhraseAndWords() {
    List<String> tokens = InventoryFreeTextSearch.identifierTokens("Testing without batch number");
    assertEquals("Testing without batch number", tokens.get(0));
    assertTrue(tokens.contains("Testing"));
    assertTrue(tokens.contains("batch"));
    assertTrue(tokens.contains("number"));
  }

  @Test
  void prefixPattern_anchorsStart() {
    assertEquals("^ABC12", InventoryFreeTextSearch.prefixPattern("ABC12"));
  }
}
