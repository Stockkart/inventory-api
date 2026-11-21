package com.inventory.documentservice.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Fixed-width text and ESC/P helpers shared by the dot-matrix documents: padding, centring,
 * wrapping, money and the printer's bold, condensed and double-width codes.
 *
 * <p>Nothing here knows about invoices or notes. The layouts that use them live in
 * {@code InvoiceTextRenderer}.
 */
public final class DotMatrixTextUtils {

  private DotMatrixTextUtils() {}

  /**
   * ESC/P emphasised mode on and off. The printer draws these lines twice, which is the only
   * bold a text-mode printer has; the preview turns the same pair into markup. Centring happens
   * before they are added, so they never count towards the width.
   */
  public static final String BOLD_ON = "\u001B\u0045";

  public static final String BOLD_OFF = "\u001B\u0046";

  public static String bold(String line) {
    return BOLD_ON + line + BOLD_OFF;
  }

  /**
   * ESC/P condensed pitch on and off - 17.1 characters to the inch instead of 10. The smallest
   * type the printer has, which is what a maker's credit line should be set in.
   */
  public static final String CONDENSED_ON = "\u000F";

  public static final String CONDENSED_OFF = "\u0012";

  public static String condensed(String line) {
    return CONDENSED_ON + line + CONDENSED_OFF;
  }

  /**
   * ESC/P double-width on and off. Every character between them is drawn twice as wide, so it
   * occupies two printing columns rather than one - which is what the eye reads as a bigger
   * character on a printer that has one type size.
   */
  public static final String DOUBLE_ON = "\u000E";

  public static final String DOUBLE_OFF = "\u0014";

  public static String doubleWide(String line) {
    return DOUBLE_ON + line + DOUBLE_OFF;
  }

  /**
   * Opens a space between every character, so a name reads as a masthead rather than a word.
   *
   * <p>The gap between words has to grow with it. Spacing the letters alone leaves the same
   * single space between "KUBER" and "PHARMA" as between its letters, and the whole name reads
   * as one word.
   */
  public static String letterSpaced(String text) {
    List<String> words = new ArrayList<>();
    for (String word : text.trim().split("\\s+")) {
      if (word.isEmpty()) {
        continue;
      }
      StringBuilder spaced = new StringBuilder(word.length() * 2);
      for (int i = 0; i < word.length(); i++) {
        if (i > 0) {
          spaced.append(' ');
        }
        spaced.append(word.charAt(i));
      }
      words.add(spaced.toString());
    }
    return String.join("   ", words);
  }

  /**
   * Centres text that will print at double width.
   *
   * <p>The padding is computed against twice the text's length, because that is the room it
   * takes on paper. Centring it by its character count would sit it a quarter of the page to
   * the left of where it lands.
   */
  public static String centreWide(String text, int width) {
    int printed = text.length() * 2;
    int left = Math.max(0, (width - printed) / 2);
    return " ".repeat(left) + doubleWide(text);
  }

  /** Trailing padding is invisible on screen and wasted carriage travel on paper; trim it. */
  public static String finish(List<String> out) {
    List<String> trimmed = new ArrayList<>(out.size());
    for (String line : out) {
      trimmed.add(line.stripTrailing());
    }
    return String.join("\n", trimmed) + "\n";
  }

  /** Centres "prefix + value" when shown and present, wrapping to the page. */
  public static void addCentred(
      List<String> out, boolean shown, String value, String prefix, int width) {
    if (!shown || !present(value)) {
      return;
    }
    for (String line : wrap((prefix == null ? "" : prefix) + value.trim(), width)) {
      out.add(centre(line, width));
    }
  }

  public static void addPart(List<String> parts, boolean shown, String value, String prefix) {
    if (shown && present(value)) {
      parts.add(prefix + value.trim());
    }
  }

  public static void addWrapped(
      List<String> lines, boolean shown, String value, String prefix, int width) {
    if (!shown || !present(value)) {
      return;
    }
    lines.addAll(wrap((prefix == null ? "" : prefix) + value.trim(), width));
  }

  /** Money with two decimals. A null value renders blank, never 0.00. */
  public static String money(BigDecimal value) {
    return value == null ? "" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  /**
   * Quantity as the preview shows it: a whole number prints without decimals, so 5 rather than
   * 5.00, and a fractional quantity keeps only the places it actually uses.
   */
  public static String quantity(BigDecimal value) {
    if (value == null) {
      return "";
    }
    BigDecimal trimmed = value.stripTrailingZeros();
    return trimmed.scale() <= 0 ? trimmed.toBigInteger().toString() : trimmed.toPlainString();
  }

  public static String pad(String raw, int width, boolean rightAlign) {
    String value = clean(raw);
    if (value.length() > width) {
      return value.substring(0, width);
    }
    String filler = " ".repeat(width - value.length());
    return rightAlign ? filler + value : value + filler;
  }

  public static String rule(int width) {
    return "-".repeat(width);
  }

  public static String centre(String raw, int width) {
    String value = clean(raw);
    if (value.length() >= width) {
      return value.substring(0, width);
    }
    return " ".repeat((width - value.length()) / 2) + value;
  }

  public static String twoColumn(String left, String right, int width) {
    String l = clean(left);
    String r = clean(right);
    int half = width / 2;
    if (l.length() > half) {
      l = l.substring(0, half);
    }
    if (r.length() > width - half) {
      r = r.substring(0, width - half);
    }
    int gap = Math.max(1, width - l.length() - r.length());
    String line = l + " ".repeat(gap) + r;
    return line.length() > width ? line.substring(0, width) : line;
  }

  /** Greedy word wrap. Words longer than the width are hard-split. */
  public static List<String> wrap(String raw, int width) {
    List<String> lines = new ArrayList<>();
    String value = clean(raw).trim();
    if (value.isEmpty()) {
      return lines;
    }
    StringBuilder current = new StringBuilder();
    for (String token : value.split("\\s+")) {
      String word = token;
      while (word.length() > width) {
        if (current.length() > 0) {
          lines.add(current.toString());
          current.setLength(0);
        }
        lines.add(word.substring(0, width));
        word = word.substring(width);
      }
      if (current.length() == 0) {
        current.append(word);
      } else if (current.length() + 1 + word.length() <= width) {
        current.append(' ').append(word);
      } else {
        lines.add(current.toString());
        current.setLength(0);
        current.append(word);
      }
    }
    if (current.length() > 0) {
      lines.add(current.toString());
    }
    return lines;
  }

  /**
   * Neutralises control characters and folds anything outside printable ASCII to '?'.
   *
   * <p>A CR or LF in a product or customer name — plausible from a pasted spreadsheet — would
   * otherwise split one row into two physical lines and destroy every column below it. The fold
   * keeps this renderer's idea of a column identical to the encoder's: ESC/P text mode is
   * single-byte, and Java counts UTF-16 units where the encoder counts runes, so the two
   * disagree on astral characters unless both reduce to one byte each.
   */
  public static String clean(String raw) {
    if (raw == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder(raw.length());
    raw.codePoints().forEach(cp -> {
      if (cp == '\t' || cp == '\n' || cp == '\r') {
        sb.append(' ');
      } else if (cp >= 0x20 && cp <= 0x7E) {
        sb.append((char) cp);
      } else {
        sb.append('?');
      }
    });
    return sb.toString();
  }

  public static String join(String separator, String a, String b) {
    if (!present(a)) {
      return nullToEmpty(b);
    }
    if (!present(b)) {
      return a;
    }
    return a + separator + b;
  }

  public static boolean isPositive(BigDecimal value) {
    return value != null && value.compareTo(BigDecimal.ZERO) > 0;
  }

  public static boolean present(String value) {
    return value != null && !value.isBlank();
  }

  public static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  /** Visibility flags default to visible when unset, matching InvoicePdfService. */
  public static boolean visible(Boolean flag) {
    return flag == null || flag;
  }
}
