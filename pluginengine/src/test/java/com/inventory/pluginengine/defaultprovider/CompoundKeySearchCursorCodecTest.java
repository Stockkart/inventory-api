package com.inventory.pluginengine.defaultprovider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompoundKeySearchCursorCodecTest {

  @Test
  void roundTripsCompoundCursorTokens() {
    String cursor =
        CompoundKeySearchCursorCodec.encode(
            List.of("1754006400000", "inv-123"));

    List<String> decoded = CompoundKeySearchCursorCodec.decode(cursor);
    assertEquals(2, decoded.size());
    assertEquals("1754006400000", decoded.get(0));
    assertEquals("inv-123", decoded.get(1));
  }

  @Test
  void encodesNullDateAsNoneToken() {
    String token = CompoundKeySearchCursorCodec.tokenForValue(null, "date");
    assertEquals(CompoundKeySearchCursorCodec.NULL_TOKEN, token);
  }

  @Test
  void encodesInstantAsEpochMillis() {
    Instant instant = Instant.parse("2026-08-01T00:00:00Z");
    String token = CompoundKeySearchCursorCodec.tokenForValue(instant, "date");
    assertEquals(String.valueOf(instant.toEpochMilli()), token);
  }

  @Test
  void ignoresSkipCursorPrefix() {
    assertTrue(
        CompoundKeySearchCursorCodec.decode(
                InventorySearchCursorMode.SKIP.schemaValue() + ":20")
            .isEmpty());
  }
}
