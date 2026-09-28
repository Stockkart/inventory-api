package com.inventory.user.service;

import com.inventory.common.exception.ValidationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/** Tokens, temporary passwords and input rules for admin accounts. */
public final class AdminCredentials {

  public static final int MIN_PASSWORD_LENGTH = 10;
  public static final int MAX_PASSWORD_LENGTH = 128;
  public static final int MAX_NAME_LENGTH = 60;
  static final int TEMPORARY_PASSWORD_LENGTH = 16;

  /** No 0/O, 1/l/I: temporary passwords are read off a screen and typed. */
  private static final String TEMPORARY_ALPHABET =
      "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
  private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
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

  public static String requireEmail(String email) {
    String normalized = normalizeEmail(email);
    if (normalized == null || !EMAIL.matcher(normalized).matches()) {
      throw new ValidationException("Enter a valid email address");
    }
    return normalized;
  }

  public static String requireName(String name) {
    String trimmed = name == null ? "" : name.trim();
    if (trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH) {
      throw new ValidationException("Name must be 1 to " + MAX_NAME_LENGTH + " characters");
    }
    return trimmed;
  }

  public static void requireAcceptablePassword(String password) {
    if (password == null || password.length() < MIN_PASSWORD_LENGTH
        || password.length() > MAX_PASSWORD_LENGTH) {
      throw new ValidationException("Password must be " + MIN_PASSWORD_LENGTH + " to "
          + MAX_PASSWORD_LENGTH + " characters");
    }
  }
}
