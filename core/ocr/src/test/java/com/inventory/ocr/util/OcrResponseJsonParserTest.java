package com.inventory.ocr.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventory.ocr.dto.ParsedInventoryItem;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class OcrResponseJsonParserTest {

  private final OcrResponseJsonParser parser = new OcrResponseJsonParser(new ObjectMapper());

  @Test
  void parsesTopLevelArray() {
    List<ParsedInventoryItem> items = parser.parse("""
        [{"name":"KNEE CAP","count":3,"costPrice":243.81,"maximumRetailPrice":512,"priceToRetail":512}]
        """);
    assertEquals(1, items.size());
    assertEquals("KNEE CAP", items.get(0).getName());
    assertEquals(3, items.get(0).getCount());
    assertEquals(new BigDecimal("243.81"), items.get(0).getCostPrice());
    assertEquals(new BigDecimal("512"), items.get(0).getMaximumRetailPrice());
  }

  @Test
  void unwrapsItemsObject() {
    List<ParsedInventoryItem> items = parser.parse("""
        {"items":[{"name":"KNEE CAP","count":3,"costPrice":243.81}]}
        """);
    assertEquals(1, items.size());
    assertEquals("KNEE CAP", items.get(0).getName());
  }

  @Test
  void stripsMarkdownFence() {
    List<ParsedInventoryItem> items = parser.parse("""
        ```json
        {"items":[{"name":"WRAP","count":1}]}
        ```
        """);
    assertEquals(1, items.size());
    assertEquals("WRAP", items.get(0).getName());
    assertNotNull(items.get(0).getCustomReminders());
  }
}
