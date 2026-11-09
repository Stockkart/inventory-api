package com.inventory.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * The converter had no tests in product; these pin the wording invoices and payslips print
 * today (note: the class Javadoc example says "One Thousand and Fourteen"; the code has never
 * put "and" after Thousand, and printed invoices depend on the code).
 */
class AmountToWordsConverterTest {

  @Test
  void rupeesAndPaise() {
    assertEquals("One Thousand Fourteen Rupees and Fifty Paise only",
        AmountToWordsConverter.convertAmountToWords(new BigDecimal("1014.50")));
  }

  @Test
  void wholeRupees() {
    assertEquals("One Thousand Fourteen Rupees only",
        AmountToWordsConverter.convertAmountToWords(new BigDecimal("1014.00")));
  }

  @Test
  void lakhsUseTheIndianSystem() {
    assertEquals("One Lakh Twenty Three Thousands Four Hundred and Fifty Six Rupees only",
        AmountToWordsConverter.convertAmountToWords(new BigDecimal("123456")));
  }

  @Test
  void zeroAndNull() {
    assertEquals("Zero Rupees only", AmountToWordsConverter.convertAmountToWords(BigDecimal.ZERO));
    assertEquals("", AmountToWordsConverter.convertAmountToWords(null));
  }
}
