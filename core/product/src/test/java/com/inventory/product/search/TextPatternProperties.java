package com.inventory.product.search;

// Feature: advanced-product-search, Property 2: Text pattern rules

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventory.common.exception.ValidationException;
import java.util.regex.Pattern;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

/**
 * <b>Validates: R3.1, R3.2, R3.3</b>
 *
 * <p>Pattern mode: the result always compiles; it matches the original text itself (every literal
 * character survives); every {@code *} became {@code .*}; a text without {@code *} contains no
 * unescaped metacharacter. Regex mode: valid, short, non-nested patterns pass unchanged; long,
 * broken or nested-quantifier patterns are rejected with a message.
 */
class TextPatternProperties {

  @Property(tries = 200)
  void patternModeIsSafeAndMatchesItself(@ForAll("texts") String text) {
    TextPattern p = TextPattern.compile(text, TextMode.PATTERN);
    Pattern compiled = p.compiled();
    String normalised = text.trim().replaceAll("\\s+", " ");
    String withoutStars = normalised.replace("*", "");

    // the pattern always finds the text it came from (with stars standing for "anything")
    assertThat(compiled.matcher(normalised.replace("*", "XYZ")).find()).isTrue();
    // and the text with the stars removed
    assertThat(compiled.matcher(withoutStars).find()).isTrue();
    // ignoring case
    assertThat(compiled.matcher(withoutStars.toUpperCase()).find()).isTrue();

    // star → .*
    long stars = normalised.chars().filter(c -> c == '*').count();
    int dotStars = p.source().split("\\.\\*", -1).length - 1;
    assertThat(dotStars).isGreaterThanOrEqualTo(stars == 0 ? 0 : 1);
    assertThat(dotStars).isLessThanOrEqualTo((int) stars);
    assertThat(p.mode()).isEqualTo(TextMode.PATTERN);
  }

  @Property(tries = 100)
  void patternModeNeverLetsMetacharactersThrough(@ForAll("metaTexts") String text) {
    TextPattern p = TextPattern.compile(text, TextMode.PATTERN);
    // a text made only of metacharacters (no star) must match itself literally
    assertThat(p.compiled().matcher(text).find()).isTrue();
    // and must not match something those characters would match as a regex
    if (text.equals(".")) {
      assertThat(p.compiled().matcher("x").find()).isFalse();
    }
  }

  @Property(tries = 100)
  void validRegexPassesUnchanged(@ForAll("safeRegexes") String regex) {
    TextPattern p = TextPattern.compile(regex, TextMode.REGEX);
    assertThat(p.source()).isEqualTo(regex.trim());
    assertThat(p.mode()).isEqualTo(TextMode.REGEX);
    assertThat(p.compiled()).isNotNull();
  }

  @Property(tries = 50)
  void overlongRegexIsRejected(@ForAll("longRegexes") String regex) {
    assertThatThrownBy(() -> TextPattern.compile(regex, TextMode.REGEX))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("too long");
  }

  @Test
  void nestedQuantifiersAreRejected() {
    for (String bad : new String[] {"(a+)+", "(.*)*", "(ab*)+", "(x+){2,}", "^(para+)*mol$"}) {
      assertThatThrownBy(() -> TextPattern.compile(bad, TextMode.REGEX))
          .as(bad)
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("repeats a group");
    }
  }

  @Test
  void brokenRegexIsRejectedWithReason() {
    assertThatThrownBy(() -> TextPattern.compile("para(mol", TextMode.REGEX))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("not valid");
  }

  @Test
  void wildcardExamples() {
    assertThat(TextPattern.compile("para*mol", TextMode.PATTERN).source()).isEqualTo("para.*mol");
    assertThat(TextPattern.compile("a.b (c)", TextMode.PATTERN).source()).isEqualTo("a\\.b \\(c\\)");
    assertThat(TextPattern.compile("**x**", TextMode.PATTERN).source()).isEqualTo(".*x.*");
    assertThatThrownBy(() -> TextPattern.compile("   ", TextMode.PATTERN)).isInstanceOf(ValidationException.class);
  }

  // ---- generators --------------------------------------------------------------------------------

  @Provide
  Arbitrary<String> texts() {
    Arbitrary<String> chars = Arbitraries.strings().withChars("abcXYZ019 .*()[]{}^$|?+\\-_/").ofMinLength(1).ofMaxLength(24);
    return chars.filter(s -> !s.trim().isEmpty());
  }

  @Provide
  Arbitrary<String> metaTexts() {
    return Arbitraries.strings().withChars(".^$|?+()[]{}\\").ofMinLength(1).ofMaxLength(8);
  }

  @Provide
  Arbitrary<String> safeRegexes() {
    return Arbitraries.of("^para", "mol$", "para.{3,5}mol", "cip|gsk", "[A-Z]{2}\\d+", "colou?r", "^SKA[0-9A-Z]+$", "a.b");
  }

  @Provide
  Arbitrary<String> longRegexes() {
    return Arbitraries.strings().withChars("abc").ofMinLength(TextPattern.MAX_REGEX_LENGTH + 1).ofMaxLength(300);
  }
}
