package com.inventory.hr.rest.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class MaskedIdentityDtoTest {

  private static final String AADHAAR = "999988887777";
  private static final String VID = "9999888877776666";
  private static final String PAN = "ABCDE1234F";

  @Test
  void constructorAcceptsTheMaskedShapes() {
    MaskedIdentityDto dto = new MaskedIdentityDto("7777", "••••••234F", true);
    assertEquals("7777", dto.aadhaarLast4());
    assertEquals("••••••234F", dto.panMasked());
    assertTrue(dto.addressPresent());
    assertNull(new MaskedIdentityDto(null, null, false).aadhaarLast4());
  }

  @Test
  void constructorRefusesAFullAadhaarOrAnythingThatIsNotFourDigits() {
    assertThrows(IllegalArgumentException.class, () -> new MaskedIdentityDto(AADHAAR, null, false));
    assertThrows(IllegalArgumentException.class, () -> new MaskedIdentityDto("777", null, false));
    assertThrows(IllegalArgumentException.class, () -> new MaskedIdentityDto("77777", null, false));
    assertThrows(IllegalArgumentException.class, () -> new MaskedIdentityDto("77a7", null, false));
    assertThrows(IllegalArgumentException.class, () -> new MaskedIdentityDto("••77", null, false));
  }

  @Test
  void constructorRefusesAFullPanOrAnUnmaskedShape() {
    assertThrows(IllegalArgumentException.class, () -> new MaskedIdentityDto(null, PAN, false));
    assertThrows(IllegalArgumentException.class, () -> new MaskedIdentityDto(null, "234F", false));
    assertThrows(IllegalArgumentException.class, () -> new MaskedIdentityDto(null, "••••", false));
    assertThrows(
        IllegalArgumentException.class, () -> new MaskedIdentityDto(null, "•••••1234F", false));
    assertThrows(
        IllegalArgumentException.class, () -> new MaskedIdentityDto(null, "••••••234f", false));
    assertThrows(
        IllegalArgumentException.class, () -> new MaskedIdentityDto(null, "XXXXXX234F", false));
  }

  @Test
  void fromRawMasksAadhaarVidAndPanAndKeepsOnlyThePresenceOfTheAddress() {
    MaskedIdentityDto dto = MaskedIdentityDto.fromRaw(AADHAAR, PAN, "12 MG Road, Bengaluru");
    assertEquals("7777", dto.aadhaarLast4());
    assertEquals("••••••234F", dto.panMasked());
    assertTrue(dto.addressPresent());

    assertEquals("6666", MaskedIdentityDto.fromRaw(VID, null, null).aadhaarLast4());
    assertFalse(MaskedIdentityDto.fromRaw(null, null, "   ").addressPresent());
    assertFalse(MaskedIdentityDto.fromRaw(null, null, null).addressPresent());
  }

  @Test
  void fromRawIgnoresSpacesAndCaseAsTypedOnDocuments() {
    MaskedIdentityDto dto = MaskedIdentityDto.fromRaw("9999 8888 7777", " abcde 1234 f ", null);
    assertEquals("7777", dto.aadhaarLast4());
    assertEquals("••••••234F", dto.panMasked());
  }

  @Test
  void fromRawShowsNothingForValuesTooShortOrNotShapedLikeTheDocument() {
    assertNull(MaskedIdentityDto.fromRaw("1234", null, null).aadhaarLast4());
    assertNull(MaskedIdentityDto.fromRaw("12-34-56", null, null).aadhaarLast4());
    assertNull(MaskedIdentityDto.fromRaw("", null, null).aadhaarLast4());
    assertNull(MaskedIdentityDto.fromRaw(null, "AB1", null).panMasked());
    assertNull(MaskedIdentityDto.fromRaw(null, "ABCDE-1234-F", null).panMasked());
    assertNull(MaskedIdentityDto.fromRaw(null, "", null).panMasked());
  }

  @Test
  void jsonNeverContainsTheRawValues() throws Exception {
    String json =
        new ObjectMapper()
            .writeValueAsString(MaskedIdentityDto.fromRaw(AADHAAR, PAN, "12 MG Road"));
    assertFalse(json.contains(AADHAAR), json);
    assertFalse(json.contains(PAN), json);
    assertFalse(json.contains("MG Road"), json);
    assertTrue(json.contains("\"aadhaarLast4\":\"7777\""), json);
    assertTrue(json.contains("\"panMasked\":\"••••••234F\""), json);
    assertTrue(json.contains("\"addressPresent\":true"), json);
  }
}
