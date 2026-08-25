package com.inventory.product.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InventoryUnifiedSearchQueryParserTest {

  @Test
  void parse_productNameOnly() {
    var parsed = InventoryUnifiedSearchQueryParser.parse("paracetamol");
    assertEquals("paracetamol", parsed.textQuery());
    assertTrue(parsed.fieldFilters().isEmpty());
  }

  @Test
  void parse_keepsBatchInProductName() {
    var parsed = InventoryUnifiedSearchQueryParser.parse("Testing without batch number");
    assertEquals("Testing without batch number", parsed.textQuery());
    assertTrue(parsed.fieldFilters().isEmpty());
  }

  @Test
  void parse_doesNotTreatBatchKeywordAsFilter() {
    var parsed = InventoryUnifiedSearchQueryParser.parse("dolo batch ABC12");
    assertEquals("dolo batch ABC12", parsed.textQuery());
    assertTrue(parsed.fieldFilters().isEmpty());
  }
}
