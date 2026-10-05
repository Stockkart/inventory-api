package com.inventory.common.util;

import java.util.Optional;
import java.util.function.Function;

/**
 * Reading an HSN or SAC code the way the GST schedule does.
 *
 * <p>Codes are typed with spaces, dots and leading text, so only the digits count. A heading sets
 * what its subheadings inherit unless they say otherwise, so a lookup that misses an eight-digit
 * code falls back to its six-, four- and two-digit parents. Shared by every HSN table (the
 * description catalogue in taxation, the rate master in product) so both read a code alike.
 */
public final class HsnCodes {

  private HsnCodes() {}

  /** The digits of a code, in order; empty for null or a code with none. */
  public static String digitsOnly(String raw) {
    if (raw == null) {
      return "";
    }
    StringBuilder out = new StringBuilder(raw.length());
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if (c >= '0' && c <= '9') {
        out.append(c);
      }
    }
    return out.toString();
  }

  /**
   * The entry for the most specific prefix of {@code code} that {@code lookup} knows, trying the
   * whole code first and no prefix shorter than {@code minLength}. Empty for a blank or all-zero
   * code.
   */
  public static <T> Optional<T> mostSpecific(
      String code, Function<String, T> lookup, int minLength) {
    String digits = digitsOnly(code);
    if (digits.isEmpty() || digits.chars().allMatch(c -> c == '0')) {
      return Optional.empty();
    }
    for (int length = digits.length(); length >= minLength; length--) {
      T found = lookup.apply(digits.substring(0, length));
      if (found != null) {
        return Optional.of(found);
      }
    }
    return Optional.empty();
  }
}
