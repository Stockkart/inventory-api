package com.inventory.product.search;

import com.inventory.common.exception.ValidationException;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Turns what the user typed into a safe, case-insensitive regular expression
 * (advanced-product-search R3.1–R3.3).
 *
 * <ul>
 *   <li>{@link TextMode#PATTERN}: every character is matched literally except {@code *}, which
 *       means "anything". {@code para*mol} becomes {@code para.*mol}. The result is matched anywhere
 *       in the value (no anchors), so {@code mol} finds "Paracetamol".
 *   <li>{@link TextMode#REGEX}: used as typed, after safety checks — at most {@value #MAX_REGEX_LENGTH}
 *       characters, must compile, and must not contain a repeated group that is itself repeated
 *       (such as {@code (a+)+} or {@code (.*)*}), the shape that makes regex engines run away.
 * </ul>
 *
 * <p>Both modes return the pattern source string Mongo will run with the {@code i} option; {@link
 * #compiled()} is only used to prove the pattern is valid.
 */
public record TextPattern(String source, TextMode mode) {

  public static final int MAX_REGEX_LENGTH = 120;

  /** Catches a quantified group followed by another quantifier: {@code (…)+*}, {@code (…)*+}, … */
  private static final Pattern NESTED_QUANTIFIER =
      Pattern.compile("\\([^()]*[*+}][^()]*\\)\\s*[*+{]");

  public TextPattern {
    if (source == null || source.isBlank()) {
      throw new IllegalArgumentException("A text pattern needs text");
    }
    mode = mode == null ? TextMode.PATTERN : mode;
  }

  /**
   * Builds the pattern for the user's text in the given mode.
   *
   * @throws ValidationException in regex mode when the text is too long, does not compile, or
   *     contains a nested quantifier
   */
  public static TextPattern compile(String text, TextMode mode) {
    String trimmed = text == null ? "" : text.trim().replaceAll("\\s+", " ");
    if (trimmed.isEmpty()) {
      throw new ValidationException("Search text is empty");
    }
    TextMode effective = mode == null ? TextMode.PATTERN : mode;
    if (effective == TextMode.PATTERN) {
      return new TextPattern(fromWildcardPattern(trimmed), TextMode.PATTERN);
    }
    if (trimmed.length() > MAX_REGEX_LENGTH) {
      throw new ValidationException(
          "Regular expression is too long (" + trimmed.length() + " characters; the limit is " + MAX_REGEX_LENGTH + ")");
    }
    if (NESTED_QUANTIFIER.matcher(trimmed).find()) {
      throw new ValidationException(
          "Regular expression repeats a group that is itself repeated (for example (a+)+ or (.*)*); this can make the search hang, so it is not allowed");
    }
    try {
      Pattern.compile(trimmed, Pattern.CASE_INSENSITIVE);
    } catch (PatternSyntaxException e) {
      throw new ValidationException("Regular expression is not valid: " + e.getDescription());
    }
    return new TextPattern(trimmed, TextMode.REGEX);
  }

  /** Escapes every regex character, then turns {@code *} into {@code .*}. */
  static String fromWildcardPattern(String text) {
    StringBuilder out = new StringBuilder(text.length() + 8);
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '*') {
        // collapse runs of * into a single .*
        if (out.length() < 2 || !out.substring(out.length() - 2).equals(".*")) {
          out.append(".*");
        }
      } else if ("\\.^$|?+()[]{}".indexOf(c) >= 0) {
        out.append('\\').append(c);
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }

  /**
   * The pattern anchored to the start of the value, for identifier-like fields (barcode, HSN, batch
   * number) where "starts with" is what people mean and where an anchored regex is far cheaper to
   * check. In regex mode the text is used as typed — the user chooses the anchors.
   */
  public String prefixSource() {
    if (mode == TextMode.REGEX || source.startsWith("^")) {
      return source;
    }
    return "^" + source;
  }

  /** The compiled pattern (case-insensitive); valid by construction. */
  public Pattern compiled() {
    return Pattern.compile(source, Pattern.CASE_INSENSITIVE);
  }

  /** True when the pattern is a simple anchored prefix (so a Mongo index can serve it). */
  public boolean isPrefix() {
    return source.startsWith("^") && !source.substring(1).matches(".*[\\\\.^$|?*+()\\[\\]{}].*");
  }
}
