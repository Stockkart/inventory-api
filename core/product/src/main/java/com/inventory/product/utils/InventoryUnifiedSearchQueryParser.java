package com.inventory.product.utils;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.util.StringUtils;

/**
 * Parses a single free-text {@code q}. The whole string is used for name/company/location
 * matching. Barcode, HSN, and batch are matched as identifier tokens (not by stripping the word
 * {@code batch} out of product names).
 */
public final class InventoryUnifiedSearchQueryParser {

  private InventoryUnifiedSearchQueryParser() {}

  public record UnifiedParsed(String textQuery, Map<String, String> fieldFilters) {}

  public static UnifiedParsed parse(String rawQ) {
    if (!StringUtils.hasText(rawQ)) {
      return new UnifiedParsed(null, Map.of());
    }
    String textQuery = normalizeWhitespace(rawQ);
    return new UnifiedParsed(StringUtils.hasText(textQuery) ? textQuery : null, new LinkedHashMap<>());
  }

  private static String normalizeWhitespace(String value) {
    if (!StringUtils.hasText(value)) {
      return "";
    }
    return value.trim().replaceAll("\\s+", " ");
  }
}
