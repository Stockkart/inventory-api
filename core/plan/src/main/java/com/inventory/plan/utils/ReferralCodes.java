package com.inventory.plan.utils;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.random.RandomGenerator;

/** Shop referral codes: {@code SK-} plus six characters from an alphabet without 0/O and 1/I/L (§11). */
public final class ReferralCodes {

  public static final String PREFIX = "SK-";
  static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
  static final int LENGTH = 6;
  /** Random codes can collide; callers retry on duplicate key this many times, then fail. */
  public static final int MAX_ATTEMPTS = 5;

  private static final SecureRandom RANDOM = new SecureRandom();

  private ReferralCodes() {}

  public static String generate() {
    return generate(RANDOM);
  }

  static String generate(RandomGenerator random) {
    StringBuilder code = new StringBuilder(PREFIX);
    for (int i = 0; i < LENGTH; i++) {
      code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
    }
    return code.toString();
  }

  /** Upper-cased and trimmed, so {@code sk-ab12cd} and {@code SK-AB12CD} are the same code. */
  public static String normalise(String code) {
    return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
  }
}
