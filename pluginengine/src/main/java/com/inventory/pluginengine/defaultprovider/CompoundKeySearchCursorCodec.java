package com.inventory.pluginengine.defaultprovider;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.springframework.util.StringUtils;

/** Encodes/decodes opaque compound-key cursors for schema-driven extension search. */
public final class CompoundKeySearchCursorCodec {

  static final String NULL_TOKEN = "none";
  static final Instant NULL_DATE_SENTINEL = Instant.parse("9999-12-31T23:59:59.999Z");

  private CompoundKeySearchCursorCodec() {}

  public static String encode(List<String> tokens) {
    if (tokens == null || tokens.isEmpty()) {
      return null;
    }
    String raw = String.join("|", tokens);
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  public static List<String> decode(String cursor) {
    if (!StringUtils.hasText(cursor)) {
      return List.of();
    }
    if (cursor.startsWith(InventorySearchCursorMode.SKIP.schemaValue() + ":")) {
      return List.of();
    }
    try {
      String raw =
          new String(Base64.getUrlDecoder().decode(cursor.trim()), StandardCharsets.UTF_8);
      if (!StringUtils.hasText(raw)) {
        return List.of();
      }
      return List.of(raw.split("\\|", -1));
    } catch (IllegalArgumentException e) {
      return List.of();
    }
  }

  static String tokenForValue(Object value, String fieldType) {
    if (value == null) {
      return "date".equalsIgnoreCase(fieldType) ? NULL_TOKEN : "";
    }
    if (value instanceof Instant instant) {
      return String.valueOf(instant.toEpochMilli());
    }
    return String.valueOf(value);
  }
}
