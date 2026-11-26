package com.inventory.hr.rest.dto;

import com.inventory.hr.utils.Masking;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What the web and the app may be told about a worker's identity documents once profile completion
 * exists (worker-app R8.8). No field can hold a full Aadhaar number or a full PAN: the constructor
 * refuses anything but the masked shapes, so a later version cannot ship a response that leaks the
 * raw value by mistake.
 *
 * <p>Nothing in this version stores these values (R8.9); the type ships with its tests so the rule
 * is inherited, not reinvented.
 *
 * @param aadhaarLast4 exactly four digits, or null when no Aadhaar is on file
 * @param panMasked {@code ••••••234F}: one or more bullets, then exactly four of {@code A–Z0–9}; or
 *     null when no PAN is on file
 * @param addressPresent whether an address is on file; the address itself is never in the DTO
 */
public record MaskedIdentityDto(String aadhaarLast4, String panMasked, boolean addressPresent) {

  /** Four digits and nothing else. */
  static final Pattern AADHAAR_LAST4 = Pattern.compile("^[0-9]{4}$");

  /** At least one bullet, then exactly four visible characters from the PAN alphabet. */
  static final Pattern PAN_MASKED = Pattern.compile("^" + Masking.BULLET + "+[A-Z0-9]{4}$");

  /** The whole of a value that may be treated as a PAN once normalised: five or more of A–Z0–9. */
  private static final Pattern PAN_LIKE = Pattern.compile("^[A-Z0-9]{5,}$");

  /** The whole of a value that may be treated as an Aadhaar number or VID: five or more digits. */
  private static final Pattern AADHAAR_LIKE = Pattern.compile("^[0-9]{5,}$");

  public MaskedIdentityDto {
    if (aadhaarLast4 != null && !AADHAAR_LAST4.matcher(aadhaarLast4).matches()) {
      throw new IllegalArgumentException("aadhaarLast4 must be exactly four digits");
    }
    if (panMasked != null && !PAN_MASKED.matcher(panMasked).matches()) {
      throw new IllegalArgumentException(
          "panMasked must be bullets followed by exactly four characters");
    }
  }

  /**
   * Builds the DTO from the raw values, masking on the way in. Whitespace inside a value is
   * ignored and PAN letters are upper-cased. A value too short to mask (fewer than five digits for
   * Aadhaar, fewer than five characters for PAN) or not shaped like the document at all becomes
   * null: showing nothing is the safe answer.
   *
   * @param aadhaar the full 12-digit number or 16-digit VID, or null
   * @param pan the full 10-character PAN, or null
   * @param address the address, or null; only its presence is kept
   */
  public static MaskedIdentityDto fromRaw(String aadhaar, String pan, String address) {
    return new MaskedIdentityDto(
        aadhaarLast4Of(aadhaar), panMaskedOf(pan), address != null && !address.isBlank());
  }

  private static String aadhaarLast4Of(String aadhaar) {
    if (aadhaar == null) {
      return null;
    }
    String digits = stripWhitespace(aadhaar);
    if (!AADHAAR_LIKE.matcher(digits).matches()) {
      return null;
    }
    return digits.substring(digits.length() - 4);
  }

  private static String panMaskedOf(String pan) {
    if (pan == null) {
      return null;
    }
    String normalised = stripWhitespace(pan).toUpperCase(Locale.ROOT);
    if (!PAN_LIKE.matcher(normalised).matches()) {
      return null;
    }
    return Masking.maskLast4(normalised);
  }

  private static String stripWhitespace(String value) {
    StringBuilder out = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (!Character.isWhitespace(c)) {
        out.append(c);
      }
    }
    return out.toString();
  }
}
