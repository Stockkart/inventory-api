// Feature: barcode-label-layout, Property 14: Formatting rules
package com.inventory.product.labels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Pattern;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.Scale;

/**
 * Property 14: Formatting rules.
 *
 * <p><b>Validates: Requirements 6.10</b>
 */
class LabelValueFormatterProperties {

  private static final Pattern CURRENCY = Pattern.compile("^\u20B9-?\\d+\\.\\d{2}$");
  private static final Pattern DATE = Pattern.compile("^\\d{2}-[A-Z][a-z]{2}-\\d{4}$");
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final DateTimeFormatter DATE_PARSER =
      DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);

  // Feature: barcode-label-layout, Property 14: Formatting rules
  @Property(tries = 100)
  void currencyHasRupeeTwoDecimalsNoGroupingAndRoundsHalfUp(
      @ForAll @BigRange(min = "-99999999", max = "99999999") @Scale(6) BigDecimal amount) {
    String result = LabelValueFormatter.format(amount, ValueType.CURRENCY);

    assertTrue(CURRENCY.matcher(result).matches(), result);
    assertFalse(result.contains(","), result);
    assertEquals(amount.setScale(2, RoundingMode.HALF_UP), new BigDecimal(result.substring(1)));
  }

  // Feature: barcode-label-layout, Property 14: Formatting rules
  @Property(tries = 100)
  void dateIsDdMmmYyyyEnglishInIst(@ForAll("instants") Instant instant) {
    String result = LabelValueFormatter.format(instant, ValueType.DATE);

    assertTrue(DATE.matcher(result).matches(), result);
    assertEquals(instant.atZone(IST).toLocalDate(), LocalDate.parse(result, DATE_PARSER));
  }

  // Feature: barcode-label-layout, Property 14: Formatting rules
  @Property(tries = 100)
  void percentageHasAtMostTwoDecimalsNoTrailingZerosAndRoundsHalfUp(
      @ForAll @BigRange(min = "-100000", max = "100000") @Scale(6) BigDecimal rate) {
    String result = LabelValueFormatter.format(rate, ValueType.PERCENTAGE);

    assertTrue(result.endsWith("%"), result);
    String numeric = result.substring(0, result.length() - 1);
    int dot = numeric.indexOf('.');
    if (dot >= 0) {
      String decimals = numeric.substring(dot + 1);
      assertTrue(decimals.length() >= 1 && decimals.length() <= 2, result);
      assertFalse(decimals.endsWith("0"), result);
    }
    assertEquals(0, rate.setScale(2, RoundingMode.HALF_UP).compareTo(new BigDecimal(numeric)), result);
  }

  // Feature: barcode-label-layout, Property 14: Formatting rules
  @Property(tries = 100)
  void nullFormatsToEmptyForEveryType(@ForAll ValueType type) {
    assertEquals("", LabelValueFormatter.format(null, type));
  }

  // Feature: barcode-label-layout, Property 14: Formatting rules
  @Property(tries = 100)
  void textIsTrimmed(@ForAll String text) {
    assertEquals(text.trim(), LabelValueFormatter.format(text, ValueType.TEXT));
  }

  @Provide
  Arbitrary<Instant> instants() {
    long min = Instant.parse("1970-01-01T00:00:00Z").getEpochSecond();
    long max = Instant.parse("2100-12-31T23:59:59Z").getEpochSecond();
    return Arbitraries.longs().between(min, max).map(Instant::ofEpochSecond);
  }
}
