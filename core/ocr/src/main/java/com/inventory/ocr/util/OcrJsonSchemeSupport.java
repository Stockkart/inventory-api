package com.inventory.ocr.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.inventory.ocr.dto.ParsedInventoryItem;

/**
 * Applies scheme fields from OCR model JSON (scheme may be "10+1" string, not an integer).
 */
public final class OcrJsonSchemeSupport {

  private OcrJsonSchemeSupport() {}

  public static void applySchemeFromJson(JsonNode n, ParsedInventoryItem item) {
    String schemeText = text(n, "scheme");
    if (schemeText == null) {
      schemeText = text(n, "purchaseScheme");
    }
    Integer legacyInt = intOnlyIfNumericNode(n, "scheme");
    SchemeTextParser.ParsedScheme parsed = SchemeTextParser.parse(
        schemeText,
        legacyInt,
        intNum(n, "schemePayFor"),
        intNum(n, "schemeFree"),
        intNum(n, "purchaseSchemePayFor"),
        intNum(n, "purchaseSchemeFree"));
    SchemeTextParser.applyToItem(item, parsed);
  }

  private static String text(JsonNode n, String key) {
    JsonNode v = n.path(key);
    if (v.isNull() || v.isMissingNode()) {
      return null;
    }
    if (v.isTextual()) {
      String s = v.asText();
      return (s != null && !s.isBlank()) ? s.trim() : null;
    }
    if (v.isNumber()) {
      return v.asText();
    }
    return null;
  }

  private static Integer intNum(JsonNode n, String key) {
    JsonNode v = n.path(key);
    if (v.isNull() || v.isMissingNode()) {
      return null;
    }
    if (v.isInt()) {
      return v.intValue();
    }
    if (v.isNumber()) {
      return v.intValue();
    }
    try {
      return Integer.parseInt(v.asText().trim());
    } catch (Exception e) {
      return null;
    }
  }

  /** Only when JSON has a plain integer — not "10+1" text. */
  private static Integer intOnlyIfNumericNode(JsonNode n, String key) {
    JsonNode v = n.path(key);
    if (v.isNull() || v.isMissingNode()) {
      return null;
    }
    if (v.isInt()) {
      return v.intValue();
    }
    if (v.isNumber()) {
      return v.intValue();
    }
    return null;
  }
}
