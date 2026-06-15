package com.inventory.product.utils;

import com.inventory.pluginengine.InventorySearchCursorCodec;
import com.inventory.pluginengine.VerticalFieldsReader;
import com.inventory.product.rest.dto.response.InventorySummaryDto;
import java.time.Instant;

/** Delegates to pluginengine cursor codec; encodes from merged summary DTOs. */
public final class InventorySearchCursor {

  private InventorySearchCursor() {}

  public static String encode(InventorySummaryDto summary) {
    if (summary == null) {
      return null;
    }
    Instant expiry = VerticalFieldsReader.expiryDateFrom(summary.getVerticalFields());
    return InventorySearchCursorCodec.encode(expiry, summary.getId());
  }

  public static InventorySearchCursorCodec.Decoded decode(String cursor) {
    return InventorySearchCursorCodec.decode(cursor);
  }
}
