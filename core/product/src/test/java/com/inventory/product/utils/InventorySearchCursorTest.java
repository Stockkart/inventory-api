package com.inventory.product.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.inventory.pluginengine.InventorySearchCursorCodec;
import com.inventory.product.rest.dto.response.InventorySummaryDto;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InventorySearchCursorTest {

  @Test
  void encodeDecode_roundTrip() {
    InventorySummaryDto dto = new InventorySummaryDto();
    dto.setId("inv-1");
    dto.setVerticalFields(Map.of("expiryDate", Instant.parse("2026-08-01T00:00:00Z")));

    String cursor = InventorySearchCursor.encode(dto);
    assertNotNull(cursor);

    InventorySearchCursorCodec.Decoded decoded = InventorySearchCursor.decode(cursor);
    assertNotNull(decoded);
    assertEquals("inv-1", decoded.inventoryId());
    assertEquals(Instant.parse("2026-08-01T00:00:00Z").toEpochMilli(), decoded.expiryEpochMillis());
  }
}
