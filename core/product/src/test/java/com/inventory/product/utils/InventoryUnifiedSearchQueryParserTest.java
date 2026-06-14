package com.inventory.product.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InventoryUnifiedSearchQueryParserTest {

  @Test
  void parse_productNameOnly() {
    var parsed = InventoryUnifiedSearchQueryParser.parse("paracetamol");
    assertEquals("paracetamol", parsed.textQuery());
    assertTrue(parsed.fieldFilters().isEmpty());
    assertFalse(parsed.fefo());
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

  @Test
  void parse_nearExpiry() {
    var parsed = InventoryUnifiedSearchQueryParser.parse("expiring 30");
    assertNull(parsed.textQuery());
    assertEquals("30", parsed.fieldFilters().get("nearExpiryDays"));
    assertTrue(parsed.sortByExpiry());
  }

  @Test
  void parse_fefoWithBatch() {
    var parsed = InventoryUnifiedSearchQueryParser.parse("fefo batch B001");
    assertTrue(parsed.fefo());
    assertEquals("B001", parsed.fieldFilters().get("batchNo"));
  }

  @Test
  void parse_isoExpiryDate() {
    var parsed = InventoryUnifiedSearchQueryParser.parse("2027-08-01");
    assertTrue(parsed.fieldFilters().containsKey("expiryAfter"));
    assertTrue(parsed.fieldFilters().containsKey("expiryBefore"));
  }
}
