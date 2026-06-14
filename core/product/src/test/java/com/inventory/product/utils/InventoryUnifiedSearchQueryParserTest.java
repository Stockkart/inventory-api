package com.inventory.product.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
  void parse_batchKeyword() {
    var parsed = InventoryUnifiedSearchQueryParser.parse("batch 1947304");
    assertNull(parsed.textQuery());
    assertEquals("1947304", parsed.fieldFilters().get("batchNo"));
  }

  @Test
  void parse_combinedNameAndBatch() {
    var parsed = InventoryUnifiedSearchQueryParser.parse("dolo batch ABC12");
    assertEquals("dolo", parsed.textQuery());
    assertEquals("ABC12", parsed.fieldFilters().get("batchNo"));
  }
}
