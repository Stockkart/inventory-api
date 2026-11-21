package com.inventory.hr.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;

/**
 * One-time pairing codes for the Attendance app (R2.3, R9.11). A code is 8 characters from an
 * alphabet without {@code 0}, {@code O}, {@code 1} and {@code I}; typing is case-insensitive and
 * spaces or hyphens between groups are ignored. Only the SHA-256 hash of a code is stored.
 */
public final class PairingCodes {

  /** 32 symbols: capital letters and digits without 0, O, 1 and I. 8 characters = 40 bits. */
  public static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

  /** Number of characters in a code. */
  public static final int LENGTH = 8;

  /** Prefix of the QR payload; the code follows after {@code #} so the page never receives it. */
  static final String QR_PREFIX = "https://stockkart.in/attendance/pair#";

  private static final HexFormat HEX = HexFormat.of();

  private PairingCodes() {}

  /** A fresh 8-character code drawn uniformly from {@link #ALPHABET}. */
  public static String generate(SecureRandom random) {
    StringBuilder sb = new StringBuilder(LENGTH);
    for (int i = 0; i < LENGTH; i++) {
      sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
    }
    return sb.toString();
  }

  /**
   * What the worker typed, made comparable: trimmed, upper-cased, with spaces and hyphens removed.
   * Null becomes the empty string. Does not check validity; see {@link #isValid(String)}.
   */
  public static String normalise(String typed) {
    if (typed == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder(typed.length());
    for (char c : typed.trim().toUpperCase(Locale.ROOT).toCharArray()) {
      if (c != ' ' && c != '-') {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  /** True when {@code code} is exactly 8 characters, each from {@link #ALPHABET}. */
  public static boolean isValid(String code) {
    if (code == null || code.length() != LENGTH) {
      return false;
    }
    for (int i = 0; i < code.length(); i++) {
      if (ALPHABET.indexOf(code.charAt(i)) < 0) {
        return false;
      }
    }
    return true;
  }

  /** Lower-case hex SHA-256 of the normalised code; the only form that is stored. */
  public static String hash(String code) {
    byte[] digest = sha256().digest(normalise(code).getBytes(StandardCharsets.UTF_8));
    return HEX.formatHex(digest);
  }

  /** The text shown as a QR code: {@code https://stockkart.in/attendance/pair#<CODE>}. */
  public static String qrPayload(String code) {
    return QR_PREFIX + code;
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required", e);
    }
  }
}
