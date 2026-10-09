package com.inventory.common.gst;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GstinTest {

  /** Real registrations published by their owners. */
  @ParameterizedTest
  @ValueSource(strings = {"27AAPFU0939F1ZV", "29AAICP2912R1ZR", "10AFBPL7000H1Z8", "03DOXPM4071K1ZE"})
  void acceptsRealGstins(String gstin) {
    assertTrue(Gstin.isValid(gstin), gstin);
    assertEquals(Optional.empty(), Gstin.problem(gstin));
  }

  @Test
  void normalizesCaseAndWhitespace() {
    Gstin g = Gstin.parse("  27aapfu0939f1zv ").orElseThrow();
    assertEquals("27AAPFU0939F1ZV", g.value());
    assertEquals("27", g.stateCode());
    assertEquals("AAPFU0939F", g.pan());
  }

  @Test
  void aMistypedCharacterFailsTheCheck() {
    // one digit changed in a real GSTIN
    assertFalse(Gstin.isValid("27AAPFU0939F1ZW"));
    assertFalse(Gstin.isValid("27AAPFU0938F1ZV"));
    assertTrue(Gstin.problem("27AAPFU0938F1ZV").orElse("").contains("check character"));
  }

  @Test
  void documentationSampleWithBadCheckDigitIsRejected() {
    // the sample GSTIN in gstinapi.in's docs is a placeholder, not a real registration
    assertFalse(Gstin.isValid("22AAAAA0000A1Z5"));
  }

  @Test
  void problemsAreExplained() {
    assertTrue(Gstin.problem("").orElse("").contains("15-character"));
    assertTrue(Gstin.problem("27AAPFU0939F1Z").orElse("").contains("15 characters"));
    assertTrue(Gstin.problem("27AAPFU0939F1AV").orElse("").contains("does not look like"));
  }
}
