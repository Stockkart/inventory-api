package com.inventory.common.util;

import java.util.Optional;

/**
 * The one Indian-mobile rule shared by sign-up ({@code AuthValidator}), the worker profile edit and
 * HR's possible-match lookup (worker-app R1.2, R4.3, Property 1).
 *
 * <p>Everything that is not an ASCII digit ({@code +}, spaces, hyphens, brackets, dots, …) is
 * dropped; the digits that remain must be exactly 10, or {@code 91} followed by 10 (the country
 * code is then removed). The answer is always the bare 10 digits. This is the rule {@code
 * AuthValidator.normalizeIndianMobile} applied before the Stockkart Team app existed, moved here
 * unchanged.
 *
 * <p>The TypeScript twin ({@code attendance-protocol/src/phone.ts}) must give the same answer on the
 * shared fixture {@code attendance-protocol/fixtures/phones.json}, which {@code
 * PhoneNumbersFixtureGeneratorTest} writes from this class.
 */
public final class PhoneNumbers {

  /** Length of an Indian mobile number without the country code. */
  public static final int LOCAL_DIGITS = 10;

  /** India's country code as it appears in a dialled number. */
  public static final String COUNTRY_CODE = "91";

  private PhoneNumbers() {}

  /**
   * Normalises a phone number as typed to the 10-digit form.
   *
   * @param raw what the user typed; {@code null} is treated as empty
   * @return the 10 digits, or empty when the input is not an Indian mobile under the rule above
   */
  public static Optional<String> normalise(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    String digits = digitsOf(raw);
    if (digits.length() == LOCAL_DIGITS) {
      return Optional.of(digits);
    }
    if (digits.length() == LOCAL_DIGITS + COUNTRY_CODE.length() && digits.startsWith(COUNTRY_CODE)) {
      return Optional.of(digits.substring(COUNTRY_CODE.length()));
    }
    return Optional.empty();
  }

  /** True when {@link #normalise} accepts {@code raw}. */
  public static boolean isValid(String raw) {
    return normalise(raw).isPresent();
  }

  /** Keeps the ASCII digits {@code 0-9} of {@code s}, in order; nothing else. */
  private static String digitsOf(String s) {
    StringBuilder out = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c >= '0' && c <= '9') {
        out.append(c);
      }
    }
    return out.toString();
  }
}
