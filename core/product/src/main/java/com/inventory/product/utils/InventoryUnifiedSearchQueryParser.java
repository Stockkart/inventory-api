package com.inventory.product.utils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
 *   <li>{@code expiring 30} / {@code near expiry 15} → nearExpiryDays
 *   <li>{@code fefo batch B001} → FEFO mode + optional batch
 *   <li>{@code 2027-08-01} → expiry on that calendar day
 * </ul>
 */
public final class InventoryUnifiedSearchQueryParser {

  private static final Pattern FEFO = Pattern.compile("(?i)\\bfefo\\b");
  private static final Pattern BATCH =
      Pattern.compile("(?i)\\bbatch[\\s:]+([\\w./-]+)");
  private static final Pattern NEAR_EXPIRY =
      Pattern.compile(
          "(?i)(?:near[- ]?expir(?:y|ing)?|expir(?:y|ing)?(?:\\s+in)?)\\s*(\\d{1,3})\\b");
  private static final Pattern DAYS_TO_EXPIRY =
      Pattern.compile("(?i)\\b(\\d{1,3})\\s*days?\\s*(?:to\\s+)?expir");
  private static final Pattern ISO_DATE = Pattern.compile("\\b(\\d{4}-\\d{2}-\\d{2})\\b");

  private InventoryUnifiedSearchQueryParser() {}

  public record UnifiedParsed(
      String textQuery,
      Map<String, String> fieldFilters,
      boolean fefo,
      boolean sortByExpiry) {}

  public static UnifiedParsed parse(String rawQ) {
    if (!StringUtils.hasText(rawQ)) {
      return new UnifiedParsed(null, Map.of(), false, false);
    }

    String working = rawQ.trim();
    Map<String, String> fieldFilters = new LinkedHashMap<>();
    boolean fefo = false;
    boolean sortByExpiry = false;

    Matcher fefoMatcher = FEFO.matcher(working);
    if (fefoMatcher.find()) {
      fefo = true;
      sortByExpiry = true;
      working = removeMatch(working, fefoMatcher);
    }

    Matcher batchMatcher = BATCH.matcher(working);
    if (batchMatcher.find()) {
      fieldFilters.put("batchNo", batchMatcher.group(1).trim());
      working = removeMatch(working, batchMatcher);
    }

    Integer nearDays = extractNearExpiryDays(working);
    if (nearDays != null) {
      fieldFilters.put("nearExpiryDays", String.valueOf(nearDays));
      sortByExpiry = true;
      working = stripNearExpiryTokens(working);
    }

    Matcher dateMatcher = ISO_DATE.matcher(working);
    if (dateMatcher.find()) {
      String day = dateMatcher.group(1);
      LocalDate local = LocalDate.parse(day);
      Instant start = local.atStartOfDay(ZoneOffset.UTC).toInstant();
      Instant end = local.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
      fieldFilters.put("expiryAfter", start.toString());
      fieldFilters.put("expiryBefore", end.toString());
      sortByExpiry = true;
      working = removeMatch(working, dateMatcher);
    }

    String textQuery = normalizeWhitespace(working);
    if (!StringUtils.hasText(textQuery) && fieldFilters.isEmpty() && !fefo) {
      textQuery = rawQ.trim();
    }

    return new UnifiedParsed(
        StringUtils.hasText(textQuery) ? textQuery : null,
        fieldFilters,
        fefo,
        sortByExpiry);
  }

  private static Integer extractNearExpiryDays(String text) {
    Matcher near = NEAR_EXPIRY.matcher(text);
    if (near.find()) {
      return parseDays(near.group(1));
    }
    Matcher days = DAYS_TO_EXPIRY.matcher(text);
    if (days.find()) {
      return parseDays(days.group(1));
    }
    return null;
  }

  private static String stripNearExpiryTokens(String text) {
    String result = NEAR_EXPIRY.matcher(text).replaceAll(" ");
    result = DAYS_TO_EXPIRY.matcher(result).replaceAll(" ");
    return result;
  }

  private static Integer parseDays(String raw) {
    try {
      int n = Integer.parseInt(raw);
      return n > 0 && n <= 365 ? n : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static String removeMatch(String input, Matcher matcher) {
    return normalizeWhitespace(input.substring(0, matcher.start()) + " " + input.substring(matcher.end()));
  }

  private static String normalizeWhitespace(String value) {
    if (!StringUtils.hasText(value)) {
      return "";
    }
    return value.trim().replaceAll("\\s+", " ");
  }
}
