// Feature: worker-app, Property 20: Masked identity DTOs never carry the raw value
package com.inventory.hr.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventory.hr.rest.dto.MaskedIdentityDto;
import java.util.Locale;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Masked identity values (design Property 20): for any string {@code s} longer than four
 * characters, {@link Masking#maskLast4} ends with the last four characters of {@code s}, has the
 * same length and is {@link Masking#BULLET} everywhere else; and the JSON of a {@link
 * MaskedIdentityDto} built from {@code s} does not contain {@code s}.
 *
 * <p>The JSON half holds for any value a document could carry. The one exclusion is a {@code s}
 * that is itself part of the DTO's fixed JSON skeleton (a field name such as {@code panMasked}):
 * that text is in every response, raw value or not, so it is not a leak and is assumed away.
 *
 * <p><b>Validates: Requirements 8.8</b>
 */
class MaskingProperties {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** The DTO's JSON when nothing is on file: the text every response carries regardless of input. */
  private static final String SKELETON = write(MaskedIdentityDto.fromRaw(null, null, null));

  /** What appears on identity documents and in addresses: digits, letters, spaces, punctuation. */
  private static final String IDENTITY_CHARS =
      "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz -,./#()'&" + Masking.BULLET;

  private static final String WHITESPACE = " \t";

  // --- arbitraries ---

  /** Any string of five or more characters, from jqwik's full default alphabet. */
  @Provide
  Arbitrary<String> longerThanFour() {
    return Arbitraries.strings().ofMinLength(5).ofMaxLength(40);
  }

  /** Five or more characters as they appear on documents and in addresses. */
  @Provide
  Arbitrary<String> identityValues() {
    return Arbitraries.strings().withChars(IDENTITY_CHARS).ofMinLength(5).ofMaxLength(40);
  }

  /** Twelve digits (Aadhaar) or sixteen digits (VID). */
  @Provide
  Arbitrary<String> aadhaarNumbers() {
    return Arbitraries.oneOf(
        Arbitraries.strings().numeric().ofLength(12), Arbitraries.strings().numeric().ofLength(16));
  }

  /** Ten characters shaped like a PAN: five letters, four digits, one letter, any case. */
  @Provide
  Arbitrary<String> pans() {
    return Combinators.combine(
            Arbitraries.strings().withCharRange('A', 'Z').ofLength(5),
            Arbitraries.strings().numeric().ofLength(4),
            Arbitraries.strings().withCharRange('A', 'Z').ofLength(1),
            Arbitraries.of(true, false))
        .as((a, d, b, lower) -> lower ? (a + d + b).toLowerCase(Locale.ROOT) : a + d + b);
  }

  /** Whitespace a user may type around or inside a document number. */
  @Provide
  Arbitrary<String> whitespace() {
    return Arbitraries.strings().withChars(WHITESPACE).ofMaxLength(2);
  }

  // --- maskLast4 ---

  @Property(tries = 300)
  void maskedValueKeepsOnlyTheLastFourAndTheLength(@ForAll("longerThanFour") String s) {
    String masked = Masking.maskLast4(s);

    assertNotNull(masked);
    assertEquals(s.length(), masked.length(), "same length as the input");
    assertTrue(masked.endsWith(s.substring(s.length() - 4)), "ends with the last four of <" + s + ">");
    for (int i = 0; i < masked.length() - 4; i++) {
      assertEquals(Masking.BULLET, masked.charAt(i), "position " + i + " of <" + masked + ">");
    }
  }

  @Property(tries = 300)
  void maskedValueNeverContainsTheRawValue(@ForAll("longerThanFour") String s) {
    // s is longer than its own last four characters, so it cannot fit in the masked form.
    assertFalse(Masking.maskLast4(s).contains(s), s);
  }

  // --- MaskedIdentityDto JSON ---

  @Property(tries = 300)
  void jsonOfADtoBuiltFromTheValueNeverContainsIt(@ForAll("identityValues") String s)
      throws Exception {
    Assume.that(!SKELETON.contains(s));

    MaskedIdentityDto dto = MaskedIdentityDto.fromRaw(s, s, s);
    String json = write(dto);

    assertFalse(json.contains(s), json);
    assertTrue(dto.addressPresent(), "an address is only reported as present");
    if (dto.aadhaarLast4() != null) {
      assertEquals(4, dto.aadhaarLast4().length());
    }
    if (dto.panMasked() != null) {
      assertTrue(dto.panMasked().startsWith(String.valueOf(Masking.BULLET)), dto.panMasked());
    }
  }

  @Property(tries = 300)
  void jsonNeverContainsAnAadhaarOrPanHoweverTyped(
      @ForAll("aadhaarNumbers") String aadhaar,
      @ForAll("pans") String pan,
      @ForAll("whitespace") String before,
      @ForAll("whitespace") String between,
      @ForAll("whitespace") String after)
      throws Exception {
    String typedAadhaar = before + aadhaar.substring(0, 4) + between + aadhaar.substring(4) + after;
    String typedPan = before + pan.substring(0, 5) + between + pan.substring(5) + after;

    MaskedIdentityDto dto = MaskedIdentityDto.fromRaw(typedAadhaar, typedPan, null);
    String json = write(dto);

    assertFalse(json.contains(aadhaar), json);
    assertFalse(json.contains(pan), json);
    assertFalse(json.contains(pan.toUpperCase(Locale.ROOT)), json);
    assertEquals(aadhaar.substring(aadhaar.length() - 4), dto.aadhaarLast4());
    assertEquals(Masking.maskLast4(pan.toUpperCase(Locale.ROOT)), dto.panMasked());
    assertFalse(dto.addressPresent());
  }

  @Property(tries = 300)
  void constructorRefusesAnyValueLongerThanFourAsAadhaarLast4(
      @ForAll("longerThanFour") String s) {
    assertThrows(IllegalArgumentException.class, () -> new MaskedIdentityDto(s, null, false), s);
  }

  @Property(tries = 300)
  void constructorRefusesAnyValueThatIsNotAlreadyMaskedAsPan(@ForAll("identityValues") String s) {
    boolean alreadyMasked = s.matches("^" + Masking.BULLET + "+[A-Z0-9]{4}$");
    Assume.that(!alreadyMasked);

    assertThrows(IllegalArgumentException.class, () -> new MaskedIdentityDto(null, s, false), s);
  }

  @Property(tries = 300)
  void constructorAcceptsWhatMaskLast4ProducesFromAPan(@ForAll("pans") String pan) {
    String masked = Masking.maskLast4(pan.toUpperCase(Locale.ROOT));

    MaskedIdentityDto dto = new MaskedIdentityDto(null, masked, false);
    assertEquals(masked, dto.panMasked());
    assertNull(dto.aadhaarLast4());
  }

  private static String write(MaskedIdentityDto dto) {
    try {
      return JSON.writeValueAsString(dto);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
