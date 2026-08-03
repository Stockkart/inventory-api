package com.inventory.product.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits an invoice number into opaque prefix + trailing numeric counter.
 */
public final class InvoiceNumberParser {

  private static final Pattern TRAILING_DIGITS = Pattern.compile("^(.*?)(\\d+)$");

  private InvoiceNumberParser() {}

  public record Parsed(String prefix, long counter, int padLength) {}

  public static Parsed parse(String lastInvoiceNo) {
    if (lastInvoiceNo == null || lastInvoiceNo.isBlank()) {
      throw new IllegalArgumentException("Last invoice number is required");
    }
    String trimmed = lastInvoiceNo.trim();
    Matcher matcher = TRAILING_DIGITS.matcher(trimmed);
    if (!matcher.matches()) {
      throw new IllegalArgumentException(
          "Invoice number must end with digits (e.g. SL-0152 or INV/PH/000149)");
    }
    String prefix = matcher.group(1);
    String digits = matcher.group(2);
    long counter;
    try {
      counter = Long.parseLong(digits);
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException("Invoice counter is too large");
    }
    if (counter <= 0) {
      throw new IllegalArgumentException("Invoice counter must be greater than 0");
    }
    return new Parsed(prefix, counter, digits.length());
  }

  public static String format(String prefix, int padLength, long counter) {
    String p = prefix != null ? prefix : "";
    int pad = padLength > 0 ? padLength : 1;
    String num = Long.toString(counter);
    if (num.length() < pad) {
      num = String.format("%0" + pad + "d", counter);
    }
    return p + num;
  }
}
