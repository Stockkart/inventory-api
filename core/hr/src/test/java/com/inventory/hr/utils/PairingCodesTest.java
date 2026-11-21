package com.inventory.hr.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

class PairingCodesTest {

  @Test
  void alphabetHas32SymbolsWithoutConfusableOnes() {
    assertEquals(32, PairingCodes.ALPHABET.length());
    assertEquals(32, PairingCodes.ALPHABET.chars().distinct().count());
    for (char c : "0O1I".toCharArray()) {
      assertFalse(PairingCodes.ALPHABET.indexOf(c) >= 0, "alphabet must not contain " + c);
    }
    assertEquals(PairingCodes.ALPHABET, PairingCodes.ALPHABET.toUpperCase());
  }

  @Test
  void generatedCodesAreEightValidCharacters() {
    SecureRandom random = new SecureRandom();
    for (int i = 0; i < 200; i++) {
      String code = PairingCodes.generate(random);
      assertEquals(8, code.length());
      assertTrue(PairingCodes.isValid(code), code);
      assertEquals(code, PairingCodes.normalise(code));
    }
  }

  @Test
  void normaliseIsForgivingAboutCaseSpacesAndHyphens() {
    assertEquals("ABCD2345", PairingCodes.normalise("abcd2345"));
    assertEquals("ABCD2345", PairingCodes.normalise(" ABCD-2345 "));
    assertEquals("ABCD2345", PairingCodes.normalise("ab cd 23 45"));
    assertEquals("ABCD2345", PairingCodes.normalise("ab-cd-23-45"));
    assertEquals("", PairingCodes.normalise(null));
    assertEquals("", PairingCodes.normalise("   "));
  }

  @Test
  void isValidRejectsWrongLengthAndCharactersOutsideTheAlphabet() {
    assertTrue(PairingCodes.isValid("ABCDEFGH"));
    assertTrue(PairingCodes.isValid("23456789"));
    assertFalse(PairingCodes.isValid(null));
    assertFalse(PairingCodes.isValid(""));
    assertFalse(PairingCodes.isValid("ABCDEFG"));
    assertFalse(PairingCodes.isValid("ABCDEFGHJ"));
    assertFalse(PairingCodes.isValid("ABCDEFG0"));
    assertFalse(PairingCodes.isValid("ABCDEFGO"));
    assertFalse(PairingCodes.isValid("ABCDEFG1"));
    assertFalse(PairingCodes.isValid("ABCDEFGI"));
    assertFalse(PairingCodes.isValid("abcdefgh"), "isValid expects a normalised code");
    assertFalse(PairingCodes.isValid("ABCD-234"));
  }

  @Test
  void hashIsSha256HexOfTheNormalisedCode() {
    // SHA-256("ABCDEFGH")
    assertEquals("9ac2197d9258257b1ae8463e4214e4cd0a578bc1517f2415928b91be4283fc48",
        PairingCodes.hash("ABCDEFGH"));
    assertEquals(PairingCodes.hash("ABCDEFGH"), PairingCodes.hash(" abcd-efgh "));
    assertNotEquals(PairingCodes.hash("ABCDEFGH"), PairingCodes.hash("ABCDEFGJ"));
    assertEquals(64, PairingCodes.hash("ABCDEFGH").length());
  }

  @Test
  void qrPayloadPutsTheCodeInTheFragment() {
    assertEquals("https://stockkart.in/attendance/pair#ABCD2345",
        PairingCodes.qrPayload("ABCD2345"));
  }
}
