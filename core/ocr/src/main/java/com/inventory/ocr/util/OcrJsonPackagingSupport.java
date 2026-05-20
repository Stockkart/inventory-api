package com.inventory.ocr.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.inventory.ocr.dto.ParsedInventoryItem;

/**
 * Reads pack / packaging fields from OCR JSON and applies {@link PackTextParser}.
 */
public final class OcrJsonPackagingSupport {

  private OcrJsonPackagingSupport() {}

  public static void applyPackagingFromJson(JsonNode n, ParsedInventoryItem item) {
    String pack = firstText(n, "pack", "packaging", "packDetail", "pkg", "pkgDetail");
    Integer unitsPerPack = firstInt(n, "unitsPerPack", "units_per_pack", "packSize");
    String baseUnit = firstText(n, "baseUnit", "base_unit", "uqc", "unit");

    if (unitsPerPack != null && unitsPerPack > 1) {
      item.setUnitsPerPack(unitsPerPack);
    }
    if (baseUnit != null && !baseUnit.isBlank()) {
      String uqc = PackTextParser.resolveBaseUnitUqc(baseUnit);
      item.setBaseUnit(uqc != null ? uqc : baseUnit.trim().toUpperCase());
    }
    if (pack != null && !pack.isBlank()) {
      item.setPackDetail(pack);
      PackTextParser.applyPackaging(item, pack, item.getName());
    } else if (item.getBaseUnit() == null && item.getName() != null) {
      PackTextParser.ParsedPack inferred = PackTextParser.parse(null, item.getName());
      if (inferred != null && inferred.baseUnitUqc() != null) {
        item.setBaseUnit(inferred.baseUnitUqc());
      }
    }
  }

  private static String firstText(JsonNode n, String... keys) {
    for (String key : keys) {
      JsonNode v = n.path(key);
      if (v.isNull() || v.isMissingNode()) {
        continue;
      }
      if (v.isTextual()) {
        String s = v.asText();
        if (s != null && !s.isBlank()) {
          return s.trim();
        }
      }
    }
    return null;
  }

  private static Integer firstInt(JsonNode n, String... keys) {
    for (String key : keys) {
      JsonNode v = n.path(key);
      if (v.isNull() || v.isMissingNode()) {
        continue;
      }
      if (v.isInt()) {
        return v.intValue();
      }
      if (v.isNumber()) {
        return v.intValue();
      }
      try {
        return Integer.parseInt(v.asText().trim());
      } catch (Exception ignored) {
        // try next key
      }
    }
    return null;
  }
}
