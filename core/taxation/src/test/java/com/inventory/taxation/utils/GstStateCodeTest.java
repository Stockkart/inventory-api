package com.inventory.taxation.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GstStateCodeTest {

  @Test
  void derivesTheStateCodeFromAGstin() {
    assertEquals("10", GstStateCode.codeFromGstin("10AFBPL7000H1Z8"));
    assertEquals("27", GstStateCode.codeFromGstin("27AAECS1234F1Z5"));
  }

  @Test
  void returnsNothingForAGstinWithNoRecognisableStateCode() {
    // 77 and 44 are not allotted. They occur in real supplier masters, so this
    // must fall through to the caller's fallback rather than invent a state.
    assertEquals("", GstStateCode.codeFromGstin("77AAECS1234F1Z5"));
    assertEquals("", GstStateCode.codeFromGstin("44AAECS1234F1Z5"));
    assertEquals("", GstStateCode.codeFromGstin(""));
    assertEquals("", GstStateCode.codeFromGstin(null));
  }

  @Test
  void formatsACodeOrANameAsThePortalExpects() {
    assertEquals("10-Bihar", GstStateCode.format("10"));
    assertEquals("10-Bihar", GstStateCode.format("Bihar"));
    assertEquals("10-Bihar", GstStateCode.format("  bihar "));
    assertEquals("27-Maharashtra", GstStateCode.format("Maharashtra"));
  }

  @Test
  void formattingIsIdempotent() {
    // The value passes through several writers; formatting an already-formatted
    // value must not produce 10-10-Bihar.
    assertEquals("10-Bihar", GstStateCode.format("10-Bihar"));
    assertEquals("10-Bihar", GstStateCode.format(GstStateCode.format("Bihar")));
  }

  @Test
  void leavesAnUnknownValueAloneRatherThanGuessing() {
    assertEquals("Atlantis", GstStateCode.format("Atlantis"));
    assertEquals("", GstStateCode.format(""));
    assertEquals("", GstStateCode.format(null));
  }

  @Test
  void placeOfSupplyFollowsTheRecipientWhenRegistered() {
    // A Bihar seller supplying a Maharashtra-registered buyer: the place of
    // supply is the buyer's state, not the seller's.
    assertEquals("27-Maharashtra",
        GstStateCode.placeOfSupply("27AAECS1234F1Z5", "10-Bihar"));
    assertEquals("10-Bihar",
        GstStateCode.placeOfSupply("10AFBPL7000H1Z8", "10-Bihar"));
  }

  @Test
  void placeOfSupplyFallsBackToTheSellerForUnregisteredBuyers() {
    // No GSTIN means no recipient registration to point anywhere else, so the
    // supplier's own state applies -- which is correct for counter sales.
    assertEquals("10-Bihar", GstStateCode.placeOfSupply("", "10-Bihar"));
    assertEquals("10-Bihar", GstStateCode.placeOfSupply(null, "Bihar"));
    assertEquals("10-Bihar", GstStateCode.placeOfSupply("77AAECS1234F1Z5", "Bihar"));
  }
}
