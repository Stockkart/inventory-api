package com.inventory.product.utils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.util.StringUtils;

/** Parses query-string params for {@code GET /inventory/search} (single {@code q} box). */
public final class InventorySearchQueryParser {

  private static final Set<String> RESERVED =
      Set.of("q", "sort", "limit", "cursor", "page", "size");

  private InventorySearchQueryParser() {}

  public record Parsed(
      String q, String sort, Integer limit, Map<String, String> fieldFilters, String cursor) {}

  public static Parsed parse(Map<String, String> query) {
    if (query == null || query.isEmpty()) {
      return new Parsed(null, null, null, Map.of(), null);
    }

    String sort = trimToNull(query.get("sort"));
    Integer limit = parsePositiveInt(query.get("limit"));
    String cursor = trimToNull(query.get("cursor"));

    String rawQ = trimToNull(query.get("q"));
    Map<String, String> fieldFilters = new LinkedHashMap<>();
    String q = rawQ;

    if (rawQ != null) {
      InventoryUnifiedSearchQueryParser.UnifiedParsed unified =
          InventoryUnifiedSearchQueryParser.parse(rawQ);
      q = unified.textQuery();
      fieldFilters.putAll(unified.fieldFilters());
    }

    if (sort == null) {
      sort = "expiryDate:asc";
    }

    return new Parsed(q, sort, limit, fieldFilters, cursor);
  }

  private static String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }

  private static Integer parsePositiveInt(String raw) {
    if (!StringUtils.hasText(raw)) {
      return null;
    }
    try {
      int n = Integer.parseInt(raw.trim());
      return n > 0 ? n : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
