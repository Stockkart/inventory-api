package com.inventory.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Worker-app R1.2: the Indian mobile rule, as examples. */
class PhoneNumbersTest {

  @ParameterizedTest
  @CsvSource({
    "9876543210, 9876543210",
    "'98765 43210', 9876543210",
    "98765-43210, 9876543210",
    "+919876543210, 9876543210",
    "919876543210, 9876543210",
    "'+91 98765 43210', 9876543210",
    "'  9876543210  ', 9876543210",
    "'(91) 9876543210', 9876543210"
  })
  void acceptsTenDigitsWithOptionalCountryCodeAndFormatting(String raw, String expected) {
    assertEquals(Optional.of(expected), PhoneNumbers.normalise(raw));
    assertTrue(PhoneNumbers.isValid(raw));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "987654321", // nine
        "09876543210", // leading zero
        "19876543210", // eleven, not 91
        "9198765432101", // thirteen
        "+91 98765 4321", // 91 + nine
        "+44 7911 123456", // another country
        "+91-abc", // no digits
        "९८७६५४३२१०" // not ASCII digits
      })
  @NullAndEmptySource
  void refusesEverythingElse(String raw) {
    assertEquals(Optional.empty(), PhoneNumbers.normalise(raw));
    assertFalse(PhoneNumbers.isValid(raw));
  }

  @ParameterizedTest
  @ValueSource(strings = {"   ", "+", "-", "() -"})
  void blankOrPunctuationOnlyIsRefused(String raw) {
    assertTrue(PhoneNumbers.normalise(raw).isEmpty());
  }
}
