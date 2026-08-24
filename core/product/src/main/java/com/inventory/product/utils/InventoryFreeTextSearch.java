package com.inventory.product.utils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * How the inventory search box is interpreted:
 *
 * <ul>
 *   <li>Multi-word substring: product {@code name}, {@code companyName}, lot {@code location}
 *   <li>Single-token prefix: {@code barcode}, {@code hsn}, {@code batchNo} (full query and each
 *       whitespace token of length ≥ 2)
 * </ul>
 */
public final class InventoryFreeTextSearch {

  private static final int MIN_TOKEN_LENGTH = 2;

  private InventoryFreeTextSearch() {}

  public static String escapeRegex(String raw) {
    if (raw == null || raw.isEmpty()) {
      return "";
    }
    return raw.replaceAll("([\\\\.^$|?*+()\\[\\]{}])", "\\\\$1");
  }

  public static String containsPattern(String raw) {
    return escapeRegex(raw.trim());
  }

  public static String prefixPattern(String raw) {
    return "^" + escapeRegex(raw.trim());
  }

  /** Full query plus distinct tokens used for barcode / HSN / batch prefix match. */
  public static List<String> identifierTokens(String raw) {
    String trimmed = raw == null ? "" : raw.trim();
    Set<String> tokens = new LinkedHashSet<>();
    if (trimmed.length() >= MIN_TOKEN_LENGTH) {
      tokens.add(trimmed);
    }
    for (String part : trimmed.split("\\s+")) {
      if (part.length() >= MIN_TOKEN_LENGTH) {
        tokens.add(part);
      }
    }
    return new ArrayList<>(tokens);
  }
}
