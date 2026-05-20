package com.inventory.ocr.util;

import com.inventory.ocr.dto.ParsedInventoryItem;

/**
 * Parses vendor Sch. / scheme cells (e.g. "10+1", "5+1", "10+2") from invoice OCR JSON.
 */
public final class SchemeTextParser {

  private SchemeTextParser() {}

  public record ParsedScheme(Integer payFor, Integer free, Integer legacyFreeOnly) {}

  public static ParsedScheme parse(
      String text,
      Integer legacySchemeInt,
      Integer payFor,
      Integer free,
      Integer purchasePayFor,
      Integer purchaseFree) {
    if (purchasePayFor != null && purchaseFree != null && purchasePayFor >= 0 && purchaseFree >= 0) {
      return new ParsedScheme(purchasePayFor, purchaseFree, null);
    }
    if (payFor != null && free != null && payFor >= 0 && free >= 0) {
      return new ParsedScheme(payFor, free, null);
    }
    if (text != null && !text.isBlank()) {
      ParsedScheme fromText = parsePlusFormat(text.trim());
      if (fromText != null) {
        return fromText;
      }
    }
    if (legacySchemeInt != null && legacySchemeInt >= 0) {
      return new ParsedScheme(null, null, legacySchemeInt);
    }
    return null;
  }

  public static void applyVendorScheme(ParsedInventoryItem item, String cellText) {
    if (item == null || cellText == null || cellText.isBlank()) {
      return;
    }
    applyToItem(item, parse(cellText, null, null, null, null, null));
  }

  public static void applyToItem(ParsedInventoryItem item, ParsedScheme parsed) {
    if (item == null || parsed == null) {
      return;
    }
    if (parsed.payFor() != null && parsed.free() != null) {
      item.setSchemePayFor(parsed.payFor());
      item.setSchemeFree(parsed.free());
      item.setPurchaseSchemePayFor(parsed.payFor());
      item.setPurchaseSchemeFree(parsed.free());
      item.setScheme(null);
      return;
    }
    if (parsed.legacyFreeOnly() != null) {
      item.setScheme(parsed.legacyFreeOnly());
    }
  }

  private static ParsedScheme parsePlusFormat(String raw) {
    String t = raw.replaceAll("\\s+", "");
    if (t.endsWith("%")) {
      return null;
    }
    int sep = t.indexOf('+');
    if (sep < 0) {
      sep = t.indexOf('/');
    }
    if (sep < 0) {
      return null;
    }
    try {
      String leftPart = t.substring(0, sep).replaceAll("[^0-9]", "");
      String rightPart = t.substring(sep + 1).replaceAll("[^0-9]", "");
      if (leftPart.isEmpty() || rightPart.isEmpty()) {
        return null;
      }
      int left = Integer.parseInt(leftPart);
      int right = Integer.parseInt(rightPart);
      if (left >= 0 && right >= 0) {
        return new ParsedScheme(left, right, null);
      }
    } catch (NumberFormatException ignored) {
      // fall through
    }
    return null;
  }
}
