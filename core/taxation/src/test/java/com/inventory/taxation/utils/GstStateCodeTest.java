package com.inventory.taxation.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GstStateCodeTest {

  @Test
  @DisplayName("reads the state a written address names")
  void readsTheStateAnAddressNames() {
    assertEquals("27", GstStateCode.codeFromAddress("14 MG Road, Andheri, Mumbai, Maharashtra 400058"));
    assertEquals("10", GstStateCode.codeFromAddress("Gandhi Chowk, Patna, bihar - 800001"));
    assertEquals("19", GstStateCode.codeFromAddress("WEST BENGAL"));
  }

  @Test
  @DisplayName("prefers the longer name where one contains another")
  void prefersTheLongerName() {
    assertEquals("23", GstStateCode.codeFromAddress("Indore, Madhya Pradesh"));
    assertEquals("09", GstStateCode.codeFromAddress("Kanpur, Uttar Pradesh"));
    assertEquals("26", GstStateCode.codeFromAddress("Daman, Dadra and Nagar Haveli and Daman and Diu"));
  }

  @Test
  @DisplayName("does not read a state out of a longer word")
  void doesNotMatchInsideALongerWord() {
    assertEquals("18", GstStateCode.codeFromAddress("Goalpara, Assam"));
    assertEquals("", GstStateCode.codeFromAddress("Goalpara"));
  }

  @Test
  @DisplayName("says nothing when the address names no state")
  void saysNothingWhenNoStateIsNamed() {
    assertEquals("", GstStateCode.codeFromAddress("Shop 4, Main Bazaar"));
    assertEquals("", GstStateCode.codeFromAddress(""));
    assertEquals("", GstStateCode.codeFromAddress(null));
  }

  @Test
  @DisplayName("a GSTIN still places a supplier that has one")
  void gstinStillPlacesTheSupplier() {
    assertEquals("19", GstStateCode.codeFromGstin("19AAOCM4713F1ZB"));
    assertEquals("10", GstStateCode.codeFromGstin("10AADFT3025B1Z4"));
    assertEquals("", GstStateCode.codeFromGstin("XX1234"));
  }
}
