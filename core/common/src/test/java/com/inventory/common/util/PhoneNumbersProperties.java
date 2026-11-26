// Feature: worker-app, Property 1: Name and phone rules are the same everywhere
package com.inventory.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * The phone half of design Property 1, Java side: {@link PhoneNumbers#normalise} accepts a string
 * iff its ASCII digits are exactly 10, or {@code 91} followed by 10, and then answers those 10
 * digits; any formatting around or between the digits ({@code +}, spaces, hyphens, dots, brackets)
 * is irrelevant; and the committed shared fixture {@code attendance-protocol/fixtures/phones.json},
 * which the TypeScript twin asserts against, carries exactly the answers this class gives.
 *
 * <p>The {@code AuthValidator} half (a WORKER sign-up is accepted iff its phone normalises) lives in
 * {@code core/user} as {@code AuthValidatorWorkerProperties}, because {@code common} cannot see it.
 *
 * <p><b>Validates: Requirements 1.2, 1.4</b>
 */
class PhoneNumbersProperties {

  private static final String ASCII_DIGITS = "0123456789";

  /** What a worker types around the digits; none of it counts. */
  private static final String FORMATTING = " -.()+/\t";

  private static final String LETTERS = "abcxyzXYZ";

  /** Digits in other scripts: Devanagari, Arabic-Indic, fullwidth. Never ASCII digits. */
  private static final String OTHER_SCRIPT_DIGITS = "०१२३४५६७८९٠١٢٣٤٥٦٧٨٩０１２３４５６７８９";

  private static final String FIXTURE = PhoneNumbersFixtureGeneratorTest.RESOURCE;

  // --- the rule, written independently of PhoneNumbers ---

  /** Property 1's phone rule as a reference model: regex instead of a character loop. */
  static Optional<String> rule(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    String digits = raw.replaceAll("[^0-9]", "");
    if (digits.length() == 10) {
      return Optional.of(digits);
    }
    if (digits.length() == 12 && digits.startsWith("91")) {
      return Optional.of(digits.substring(2));
    }
    return Optional.empty();
  }

  // --- arbitraries ---

  @Provide
  Arbitrary<String> tenDigits() {
    return Arbitraries.strings().withChars(ASCII_DIGITS).ofLength(10);
  }

  /** How the country code may be written in front of the ten digits, including not at all. */
  @Provide
  Arbitrary<String> countryCodePrefixes() {
    return Arbitraries.of("", "", "91", "+91", "+91 ", "+91-", "(91) ", "91 ", "(+91)", "+91 - ");
  }

  /** Separator text placed before, between and after the ten digits. */
  @Provide
  Arbitrary<List<String>> separators() {
    return Arbitraries.of("", "", "", "", " ", "-", ".", "  ", " - ", "(", ")", "/")
        .list()
        .ofSize(11);
  }

  @Provide
  Arbitrary<String> surroundingWhitespace() {
    return Arbitraries.strings().withChars(" \t\n").ofMaxLength(3);
  }

  /** Any string at all, biased towards phone-like text so both answers are common. */
  @Provide
  Arbitrary<String> anyString() {
    Arbitrary<String> phoneLike =
        Arbitraries.strings()
            .withChars(ASCII_DIGITS + ASCII_DIGITS + FORMATTING + LETTERS + OTHER_SCRIPT_DIGITS)
            .ofMaxLength(20);
    Arbitrary<String> unicode = Arbitraries.strings().ofMaxLength(20);
    Arbitrary<String> decorated =
        Combinators.combine(
                tenDigits(), countryCodePrefixes(), separators(), surroundingWhitespace(),
                surroundingWhitespace())
            .as(PhoneNumbersProperties::decorate);
    return Arbitraries.oneOf(phoneLike, unicode, decorated).injectNull(0.02);
  }

  /** ASCII digits only, 0–16 of them, so every length around 10 and 12 is drawn. */
  @Provide
  Arbitrary<String> digitStrings() {
    return Arbitraries.strings().withChars(ASCII_DIGITS).ofMaxLength(16);
  }

  /** Strings with no ASCII digit at all: formatting, letters, digits of other scripts. */
  @Provide
  Arbitrary<String> noAsciiDigits() {
    return Arbitraries.strings()
        .withChars(FORMATTING + LETTERS + OTHER_SCRIPT_DIGITS)
        .ofMaxLength(14);
  }

  @Provide
  Arbitrary<JsonNode> fixtureCases() {
    return Arbitraries.of(committedFixtureCases());
  }

  private static String decorate(
      String digits, String prefix, List<String> seps, String before, String after) {
    StringBuilder sb = new StringBuilder(before).append(prefix);
    for (int i = 0; i < digits.length(); i++) {
      sb.append(seps.get(i)).append(digits.charAt(i));
    }
    return sb.append(seps.get(digits.length())).append(after).toString();
  }

  // --- properties ---

  @Property(tries = 300)
  void normaliseAgreesWithTheRuleOnAnyString(@ForAll("anyString") String raw) {
    Optional<String> expected = rule(raw);

    assertEquals(expected, PhoneNumbers.normalise(raw), "<" + raw + ">");
    assertEquals(expected.isPresent(), PhoneNumbers.isValid(raw), "<" + raw + ">");
  }

  @Property(tries = 300)
  void anyFormattingOfTenDigitsWithOrWithoutTheCountryCodeNormalisesToTheTenDigits(
      @ForAll("tenDigits") String digits,
      @ForAll("countryCodePrefixes") String prefix,
      @ForAll("separators") List<String> seps,
      @ForAll("surroundingWhitespace") String before,
      @ForAll("surroundingWhitespace") String after) {
    String typed = decorate(digits, prefix, seps, before, after);

    assertEquals(Optional.of(digits), PhoneNumbers.normalise(typed), "typed: <" + typed + ">");
    assertTrue(PhoneNumbers.isValid(typed));
  }

  @Property(tries = 300)
  void anAcceptedAnswerIsTenAsciiDigitsAndNormalisesToItself(@ForAll("anyString") String raw) {
    Optional<String> answer = PhoneNumbers.normalise(raw);
    if (answer.isEmpty()) {
      return;
    }
    String digits = answer.get();

    assertEquals(PhoneNumbers.LOCAL_DIGITS, digits.length(), "<" + raw + "> → " + digits);
    for (char c : digits.toCharArray()) {
      assertTrue(c >= '0' && c <= '9', "non-ASCII digit in " + digits);
    }
    assertEquals(answer, PhoneNumbers.normalise(digits), "normalising twice changes the answer");
    assertEquals(answer, PhoneNumbers.normalise("+" + PhoneNumbers.COUNTRY_CODE + digits));
    assertEquals(answer, PhoneNumbers.normalise(PhoneNumbers.COUNTRY_CODE + digits));
  }

  @Property(tries = 300)
  void digitsOnlyAreAcceptedExactlyAtTenOrAtNinetyOneFollowedByTen(
      @ForAll("digitStrings") String digits) {
    boolean expected =
        digits.length() == PhoneNumbers.LOCAL_DIGITS
            || (digits.length() == PhoneNumbers.LOCAL_DIGITS + PhoneNumbers.COUNTRY_CODE.length()
                && digits.startsWith(PhoneNumbers.COUNTRY_CODE));

    assertEquals(expected, PhoneNumbers.isValid(digits), "<" + digits + ">");
    if (expected) {
      String tail = digits.substring(digits.length() - PhoneNumbers.LOCAL_DIGITS);
      assertEquals(Optional.of(tail), PhoneNumbers.normalise(digits));
    }
  }

  @Property(tries = 200)
  void aStringWithoutAsciiDigitsIsRefused(@ForAll("noAsciiDigits") String raw) {
    assertEquals(Optional.empty(), PhoneNumbers.normalise(raw), "<" + raw + ">");
  }

  @Property(tries = 100)
  void theSharedFixtureCarriesExactlyTheJavaAnswers(@ForAll("fixtureCases") JsonNode entry) {
    String input = entry.path("input").asText();
    JsonNode normalised = entry.path("normalised");
    String expected = normalised.isNull() ? null : normalised.asText();

    assertEquals(
        Optional.ofNullable(expected), PhoneNumbers.normalise(input), entry.path("name").asText());
    assertEquals(rule(input), PhoneNumbers.normalise(input), entry.path("name").asText());
  }

  // --- helpers ---

  private static List<JsonNode> committedFixtureCases() {
    try (InputStream in =
        PhoneNumbersProperties.class.getClassLoader().getResourceAsStream(FIXTURE)) {
      if (in == null) {
        fail(FIXTURE + " is not on the test classpath; run PhoneNumbersFixtureGeneratorTest");
      }
      JsonNode root = new ObjectMapper().readTree(in);
      List<JsonNode> cases = new ArrayList<>();
      root.path("cases").forEach(cases::add);
      assertTrue(cases.size() >= 2, "the fixture has cases");
      return cases;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
