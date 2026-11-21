package com.inventory.user.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/** Tokens and temporary passwords for admin accounts. Input rules: {@code AdminUserValidator}. */
public final class AdminCredentials {

  static final int TEMPORARY_PASSWORD_LENGTH = 16;

  /** No 0/O, 1/l/I: temporary passwords are read off a screen and typed. */
  private static final String TEMPORARY_ALPHABET =
      "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
  private static final SecureRandom RANDOM = new SecureRandom();

  private AdminCredentials() {
  }

  public static String newSessionToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public static String hashToken(String token) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  public static String newTemporaryPassword() {
    StringBuilder password = new StringBuilder(TEMPORARY_PASSWORD_LENGTH);
    for (int i = 0; i < TEMPORARY_PASSWORD_LENGTH; i++) {
      password.append(TEMPORARY_ALPHABET.charAt(RANDOM.nextInt(TEMPORARY_ALPHABET.length())));
    }
    return password.toString();
  }

  /** Trimmed, lower-cased email, or null when blank. */
  public static String normalizeEmail(String email) {
    if (email == null || email.isBlank()) {
      return null;
    }
    return email.trim().toLowerCase(Locale.ROOT);
  }
}
