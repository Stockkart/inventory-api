package com.inventory.product.utils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * Parses a single free-text {@code q} into core text search + extension field filters.
 *
 * <p>Examples:
 *
 * <ul>
 *   <li>{@code paracetamol} → text query
 *   <li>{@code batch 1947304} → batchNo filter
 *   <li>{@code dolo batch ABC12} → text + batchNo
 * </ul>
 */
public final class InventoryUnifiedSearchQueryParser {

  private static final Pattern BATCH =
      Pattern.compile("(?i)\\bbatch[\\s:]+([\\w./-]+)");

  private InventoryUnifiedSearchQueryParser() {}

  public record UnifiedParsed(String textQuery, Map<String, String> fieldFilters) {}

  public static UnifiedParsed parse(String rawQ) {
    if (!StringUtils.hasText(rawQ)) {
      return new UnifiedParsed(null, Map.of());
    }

    String working = rawQ.trim();
    Map<String, String> fieldFilters = new LinkedHashMap<>();

    Matcher batchMatcher = BATCH.matcher(working);
    if (batchMatcher.find()) {
      fieldFilters.put("batchNo", batchMatcher.group(1).trim());
      working = removeMatch(working, batchMatcher);
    }

    String textQuery = normalizeWhitespace(working);
    if (!StringUtils.hasText(textQuery) && fieldFilters.isEmpty()) {
      textQuery = rawQ.trim();
    }

    return new UnifiedParsed(
        StringUtils.hasText(textQuery) ? textQuery : null, fieldFilters);
  }

  private static String removeMatch(String input, Matcher matcher) {
    return normalizeWhitespace(
        input.substring(0, matcher.start()) + " " + input.substring(matcher.end()));
  }

  private static String normalizeWhitespace(String value) {
    if (!StringUtils.hasText(value)) {
      return "";
    }
    return value.trim().replaceAll("\\s+", " ");
  }
}
