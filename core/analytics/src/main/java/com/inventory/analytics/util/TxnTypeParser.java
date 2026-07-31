package com.inventory.analytics.util;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.util.StringUtils;

/** Parses a comma-separated {@code txnTypes} query parameter into a normalised set. */
public final class TxnTypeParser {

  private TxnTypeParser() {}

  /**
   * Splits a CSV string into trimmed, upper-cased, non-blank tokens. Returns an empty set for null
   * or blank input.
   */
  public static Set<String> parse(String csv) {
    if (!StringUtils.hasText(csv)) {
      return Set.of();
    }
    return Arrays.stream(csv.split(","))
        .map(String::trim)
        .filter(StringUtils::hasText)
        .map(String::toUpperCase)
        .collect(Collectors.toSet());
  }
}
