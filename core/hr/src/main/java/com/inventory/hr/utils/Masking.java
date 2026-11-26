package com.inventory.hr.utils;

/**
 * Masking for identity values that a later version will collect (worker-app R8.8). Shipped now,
 * with its tests, so the profile-completion version inherits the rule instead of inventing one.
 *
 * <p>Pure: no I/O, no state. Kept out of the MapStruct mappers for the same reason as {@link
 * EmployeeMaskingUtils}.
 */
public final class Masking {

  /** The character that replaces every hidden position. */
  public static final char BULLET = '\u2022';

  /** What a value of four or fewer characters becomes: nothing of it is shown. */
  public static final String ALL_HIDDEN = "\u2022\u2022\u2022\u2022";

  private Masking() {}

  /**
   * {@code ••••1234}: the same length as the input, {@link #BULLET} everywhere but the last four
   * characters. The input is taken as is (no trimming) so the length rule holds exactly.
   *
   * <p>Null stays null. A value of four or fewer characters would be shown whole by "last four",
   * so it becomes {@link #ALL_HIDDEN} instead.
   */
  public static String maskLast4(String value) {
    if (value == null) {
      return null;
    }
    int length = value.length();
    if (length <= 4) {
      return ALL_HIDDEN;
    }
    StringBuilder masked = new StringBuilder(length);
    for (int i = 0; i < length - 4; i++) {
      masked.append(BULLET);
    }
    masked.append(value, length - 4, length);
    return masked.toString();
  }
}
