package com.inventory.product.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
  }

  @Test
  void parse_fefoRoutesFlag() {
    InventorySearchQueryParser.Parsed parsed =
        InventorySearchQueryParser.parse(Map.of("q", "fefo batch B9"));
    assertTrue(parsed.fefo());
    assertEquals("B9", parsed.fieldFilters().get("batchNo"));
  }
}
