package com.inventory.product.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class InventorySearchQueryParserTest {

  @Test
  void parse_unifiedQOnly() {
    InventorySearchQueryParser.Parsed parsed =
        InventorySearchQueryParser.parse(
            Map.of("q", "dolo batch ABC12", "limit", "25"));

    assertEquals("dolo batch ABC12", parsed.q());
    assertEquals(25, parsed.limit());
    assertTrue(parsed.fieldFilters().isEmpty());
    assertNull(parsed.sort());
  }

  @Test
  void parse_explicitSort() {
    InventorySearchQueryParser.Parsed parsed =
        InventorySearchQueryParser.parse(
            Map.of("q", "paracetamol", "sort", "expiryDate:desc"));
    assertEquals("expiryDate:desc", parsed.sort());
  }

  @Test
  void parse_cursorParam() {
    InventorySearchQueryParser.Parsed parsed =
        InventorySearchQueryParser.parse(
            Map.of("q", "dolo", "cursor", "abc123", "limit", "10"));
    assertEquals("abc123", parsed.cursor());
    assertEquals(10, parsed.limit());
  }

  @Test
  void parse_includeZeroStockDefaultsToTrue() {
    InventorySearchQueryParser.Parsed parsed =
        InventorySearchQueryParser.parse(Map.of("q", "dolo"));
    assertTrue(parsed.includeZeroStock());
  }

  @Test
  void parse_includeZeroStockFalseIsHonouredAndNotTreatedAsAFieldFilter() {
    InventorySearchQueryParser.Parsed parsed =
        InventorySearchQueryParser.parse(Map.of("q", "dolo", "includeZeroStock", "false"));
    assertFalse(parsed.includeZeroStock());
    assertTrue(parsed.fieldFilters().isEmpty());
  }

  @Test
  void parse_pageParam() {
    InventorySearchQueryParser.Parsed parsed =
        InventorySearchQueryParser.parse(
            Map.of("q", "baby", "limit", "10", "page", "2"));
    assertEquals("baby", parsed.q());
    assertEquals(10, parsed.limit());
    assertEquals(2, parsed.page());
  }
}
