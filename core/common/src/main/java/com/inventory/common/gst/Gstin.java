package com.inventory.common.gst;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * A GSTIN that has passed the offline checks, so the rest of the code can trust its shape.
 *
 * <p>Format: {@code SS PPPPPPPPPP E Z C} — a two-digit state code, the ten-character PAN, an entity
 * number, the letter Z (reserved) and a check character. The check character is a mod-36 Luhn
 * variant over the first fourteen characters, so a mistyped digit is caught before anything is
 * looked up or stored.
 */
public record Gstin(String value) {

  /** Allows alphanumerics in the entity and check slots, which is what the registry actually issues. */
  private static final Pattern SHAPE =
      Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$");
  private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

  public Gstin {
    if (!isValid(value)) {
      throw new IllegalArgumentException("Not a valid GSTIN: " + value);
    }
  }

  /** Uppercased, trimmed and validated; empty when the text is not a GSTIN. */
  public static Optional<Gstin> parse(String raw) {
    String normalized = normalize(raw);
    return isValid(normalized) ? Optional.of(new Gstin(normalized)) : Optional.empty();
  }

  public static String normalize(String raw) {
    return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
  }

  /** Shape and check character both right. */
  public static boolean isValid(String raw) {
    String s = normalize(raw);
    return SHAPE.matcher(s).matches() && s.charAt(14) == checkCharacter(s.substring(0, 14));
  }

  /** Why a GSTIN is rejected, in plain words, or empty when it is fine. */
  public static Optional<String> problem(String raw) {
    String s = normalize(raw);
    if (!StringUtils.hasText(s)) {
      return Optional.of("Enter the 15-character GSTIN");
    }
    if (s.length() != 15) {
      return Optional.of("A GSTIN has 15 characters; this one has " + s.length());
    }
    if (!SHAPE.matcher(s).matches()) {
      return Optional.of("This does not look like a GSTIN (2 digits, PAN, entity number, Z, check character)");
    }
    if (s.charAt(14) != checkCharacter(s.substring(0, 14))) {
      return Optional.of("The check character does not match — one of the characters is mistyped");
    }
    return Optional.empty();
  }

  /** The two-digit state code the GSTIN was issued under. */
  public String stateCode() {
    return value.substring(0, 2);
  }

  /** The PAN embedded in the GSTIN. */
  public String pan() {
    return value.substring(2, 12);
  }

  /** The mod-36 Luhn check character for the first fourteen characters. */
  static char checkCharacter(String first14) {
    int sum = 0;
    for (int i = 0; i < first14.length(); i++) {
      int code = ALPHABET.indexOf(first14.charAt(i));
      int factor = (i % 2 == 0) ? 1 : 2;
      int product = code * factor;
      sum += product / 36 + product % 36;
    }
    int check = (36 - (sum % 36)) % 36;
    return ALPHABET.charAt(check);
  }

  @Override
  public String toString() {
    return value;
  }
}
