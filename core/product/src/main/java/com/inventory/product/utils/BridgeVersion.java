package com.inventory.product.utils;

import java.util.Optional;

/**
 * Compares print bridge versions ("0.12.0", "v0.12.0", "0.13.0-rc1"). Only the three numeric
 * parts count; a suffix is ignored.
 */
public final class BridgeVersion {

  private BridgeVersion() {}

  /**
   * Whether {@code reported} is at least {@code minimum}. A version that cannot be read is not:
   * a bridge that reports nothing usable is treated as one that needs updating, never as current.
   */
  public static boolean isAtLeast(String reported, String minimum) {
    Optional<int[]> left = parse(reported);
    Optional<int[]> right = parse(minimum);
    if (left.isEmpty() || right.isEmpty()) {
      return false;
    }
    int[] a = left.get();
    int[] b = right.get();
    for (int i = 0; i < 3; i++) {
      if (a[i] != b[i]) {
        return a[i] > b[i];
      }
    }
    return true;
  }

  static Optional<int[]> parse(String version) {
    if (version == null) {
      return Optional.empty();
    }
    String core = version.trim();
    if (core.startsWith("v") || core.startsWith("V")) {
      core = core.substring(1);
    }
    int suffix = core.indexOf('-');
    if (suffix >= 0) {
      core = core.substring(0, suffix);
    }
    String[] parts = core.split("\\.");
    if (parts.length != 3) {
      return Optional.empty();
    }
    int[] numbers = new int[3];
    try {
      for (int i = 0; i < 3; i++) {
        numbers[i] = Integer.parseInt(parts[i]);
      }
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
    return Optional.of(numbers);
  }
}
