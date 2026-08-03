package com.inventory.product.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FinancialYearAndInvoiceNumberParserTest {

  @ParameterizedTest
  @CsvSource({
    "2026-08-03, 2026-27",
    "2027-03-15, 2026-27",
    "2027-04-01, 2027-28",
    "2025-04-01, 2025-26",
    "2025-03-31, 2024-25"
  })
  void labelFor_indianFy(String date, String expected) {
    assertEquals(expected, FinancialYear.labelFor(LocalDate.parse(date)));
  }

  @ParameterizedTest
  @CsvSource({
    "INV-00001, INV-, 1, 5",
    "SL-0152, SL-, 152, 4",
    "A001023, A, 1023, 6",
    "DN00001, DN, 1, 5",
    "INV/PH/000149, INV/PH/, 149, 6",
    "M00481, M, 481, 5",
    "A000011, A, 11, 6"
  })
  void parse_samples(String input, String prefix, long counter, int pad) {
    InvoiceNumberParser.Parsed parsed = InvoiceNumberParser.parse(input);
    assertEquals(prefix, parsed.prefix());
    assertEquals(counter, parsed.counter());
    assertEquals(pad, parsed.padLength());
  }

  @Test
  void parse_pureNumeric() {
    InvoiceNumberParser.Parsed parsed = InvoiceNumberParser.parse("5376108373");
    assertEquals("", parsed.prefix());
    assertEquals(5376108373L, parsed.counter());
    assertEquals(10, parsed.padLength());
  }

  @Test
  void parse_rejectsNoDigits() {
    assertThrows(IllegalArgumentException.class, () -> InvoiceNumberParser.parse("INV-"));
  }

  @Test
  void format_preservesPadAndAllowsOverflow() {
    assertEquals("SL-0153", InvoiceNumberParser.format("SL-", 4, 153));
    assertEquals("INV-100000", InvoiceNumberParser.format("INV-", 5, 100000));
  }
}
