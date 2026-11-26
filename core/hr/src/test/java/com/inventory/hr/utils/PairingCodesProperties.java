// Feature: HR-module, Property 8: Pairing codes are well formed and typing is forgiving
package com.inventory.hr.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Pairing codes (design Property 8): a generated code is 8 characters from {@link
 * PairingCodes#ALPHABET} and never contains {@code 0}, {@code O}, {@code 1} or {@code I}; whatever
 * case, interior spaces or hyphens and surrounding whitespace the worker types, {@code normalise}
 * gives the code back and {@code hash} is unchanged; a string with a character outside the alphabet
 * or of a length other than 8 is never valid; and the QR payload is the fixed prefix followed by the
 * code in the fragment.
 *
 * <p><b>Validates: Requirements 2.3</b>
 */
class PairingCodesProperties {

  /** Characters a worker might type that are not in the alphabet (confusables included). */
  private static final String OUTSIDE_ALPHABET = "0O1Iabcxyz_./:,;!@#$%^&*()+=?éЖ";

  private static final String WHITESPACE = " \t\n";

  // --- arbitraries ---

  @Provide
  Arbitrary<String> validCodes() {
    return Arbitraries.strings().withChars(PairingCodes.ALPHABET).ofLength(PairingCodes.LENGTH);
  }

  /** Separator text placed between (and around) the characters of a typed code. */
  @Provide
  Arbitrary<List<String>> separators() {
    return Arbitraries.of("", "", "", " ", "-", "  ", " - ", "--", "- ")
        .list()
        .ofSize(PairingCodes.LENGTH + 1);
  }

  /** Which characters of the typed code are lower-cased. */
  @Provide
  Arbitrary<List<Boolean>> lowerCaseMask() {
    return Arbitraries.of(true, false).list().ofSize(PairingCodes.LENGTH);
  }

  @Provide
  Arbitrary<String> surroundingWhitespace() {
    return Arbitraries.strings().withChars(WHITESPACE).ofMaxLength(3);
  }

  /** Strings of length 0–12 drawn from the alphabet plus characters outside it. */
  @Provide
  Arbitrary<String> anyTypedString() {
    return Arbitraries.strings()
        .withChars(PairingCodes.ALPHABET + OUTSIDE_ALPHABET)
        .ofMaxLength(PairingCodes.LENGTH + 4);
  }

  @Provide
  Arbitrary<Character> outsideChars() {
    return Arbitraries.of(OUTSIDE_ALPHABET.chars().mapToObj(c -> (char) c).toList());
  }

  /** A valid code with one character swapped for one outside the alphabet. */
  @Provide
  Arbitrary<String> codesWithOneBadChar() {
    return Combinators.combine(
            validCodes(), Arbitraries.integers().between(0, PairingCodes.LENGTH - 1), outsideChars())
        .as((code, i, bad) -> code.substring(0, i) + bad + code.substring(i + 1));
  }

  private static String decorate(
      String code, List<Boolean> lower, List<String> seps, String before, String after) {
    StringBuilder sb = new StringBuilder(before);
    for (int i = 0; i < code.length(); i++) {
      sb.append(seps.get(i));
      char c = code.charAt(i);
      sb.append(lower.get(i) ? Character.toLowerCase(c) : c);
    }
    sb.append(seps.get(code.length())).append(after);
    return sb.toString();
  }

  private static SecureRandom seeded(long seed) {
    try {
      SecureRandom r = SecureRandom.getInstance("SHA1PRNG");
      r.setSeed(seed);
      return r;
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  // --- properties ---

  @Property(tries = 300)
  void generatedCodesAreEightCharactersFromTheAlphabetWithoutConfusables(@ForAll long seed) {
    String code = PairingCodes.generate(seeded(seed));

    assertEquals(PairingCodes.LENGTH, code.length());
    for (char c : code.toCharArray()) {
      assertTrue(PairingCodes.ALPHABET.indexOf(c) >= 0, "character outside alphabet: " + c);
    }
    for (char c : "0O1I".toCharArray()) {
      assertEquals(-1, code.indexOf(c), "confusable " + c + " in " + code);
    }
    assertTrue(PairingCodes.isValid(code));
    assertEquals(code, PairingCodes.normalise(code), "a generated code is already normalised");
  }

  @Property(tries = 300)
  void anyDecorationOfAValidCodeNormalisesBackToItAndHashesTheSame(
      @ForAll("validCodes") String code,
      @ForAll("lowerCaseMask") List<Boolean> lower,
      @ForAll("separators") List<String> seps,
      @ForAll("surroundingWhitespace") String before,
      @ForAll("surroundingWhitespace") String after) {
    String typed = decorate(code, lower, seps, before, after);

    assertEquals(code, PairingCodes.normalise(typed), "typed: <" + typed + ">");
    assertTrue(PairingCodes.isValid(PairingCodes.normalise(typed)));
    assertEquals(PairingCodes.hash(code), PairingCodes.hash(typed));
    assertEquals(64, PairingCodes.hash(typed).length());
  }

  @Property(tries = 300)
  void isValidIsTrueExactlyForEightCharactersAllFromTheAlphabet(
      @ForAll("anyTypedString") String s) {
    boolean expected =
        s.length() == PairingCodes.LENGTH
            && s.chars().allMatch(c -> PairingCodes.ALPHABET.indexOf(c) >= 0);

    assertEquals(expected, PairingCodes.isValid(s), "<" + s + ">");
  }

  @Property(tries = 200)
  void aValidCodeWithOneCharacterOutsideTheAlphabetIsNeverValid(
      @ForAll("codesWithOneBadChar") String s) {
    assertFalse(PairingCodes.isValid(s), s);
  }

  @Property(tries = 200)
  void aValidCodeOfAnyOtherLengthIsNeverValid(
      @ForAll("validCodes") String code,
      @ForAll("validCodes") String more,
      @ForAll int lengthDelta) {
    int delta = Math.floorMod(lengthDelta, 2 * PairingCodes.LENGTH + 1) - PairingCodes.LENGTH;
    if (delta == 0) {
      return; // same length: covered by the other properties
    }
    String s = delta < 0 ? code.substring(0, PairingCodes.LENGTH + delta) : code + more.substring(0, delta);

    assertEquals(PairingCodes.LENGTH + delta, s.length());
    assertFalse(PairingCodes.isValid(s), s);
  }

  @Property(tries = 200)
  void qrPayloadIsThePrefixThenTheCodeInTheFragment(@ForAll("validCodes") String code) {
    String payload = PairingCodes.qrPayload(code);

    assertTrue(payload.startsWith(PairingCodes.QR_PREFIX), payload);
    assertEquals("https://stockkart.in/attendance/pair#" + code, payload);
    int hash = payload.indexOf('#');
    assertEquals(PairingCodes.QR_PREFIX.length() - 1, hash, "the code sits after the first #");
    String fragment = payload.substring(hash + 1);
    assertEquals(code, fragment);
    assertEquals(code, PairingCodes.normalise(fragment));
    assertTrue(PairingCodes.isValid(fragment));
    assertEquals(hash, payload.lastIndexOf('#'), "the code has no # of its own");
  }
}
