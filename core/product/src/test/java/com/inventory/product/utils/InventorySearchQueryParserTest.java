package com.inventory.product.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class InventorySearchQueryParserTest {

  @Test
  void parse_unifiedQOnly() {
    InventorySearchQueryParser.Parsed parsed =
        InventorySearchQueryParser.parse(
            Map.of("q", "dolo batch ABC12", "limit", "25"));

    assertEquals("dolo", parsed.q());
    assertEquals(25, parsed.limit());
    assertEquals("ABC12", parsed.fieldFilters().get("batchNo"));
    assertEquals("expiryDate:asc", parsed.sort());
  }

  @Test
  void parse_defaultsExpirySort() {
    InventorySearchQueryParser.Parsed parsed =
        InventorySearchQueryParser.parse(Map.of("q", "paracetamol"));
    assertEquals("expiryDate:asc", parsed.sort());
  }

  @Test
  void parse_cursorParam() {
    InventorySearchQueryParser.Parsed parsed =
        InventorySearchQueryParser.parse(
            Map.of("q", "dolo", "cursor", "abc123", "limit", "10"));
    assertEquals("abc123", parsed.cursor());
    assertEquals(10, parsed.limit());
  }
}
