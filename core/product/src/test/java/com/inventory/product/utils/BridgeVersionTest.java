package com.inventory.product.utils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BridgeVersionTest {

  @Test
  void comparesEachPartAsANumberNotAsText() {
    assertTrue(BridgeVersion.isAtLeast("0.12.0", "0.11.0"));
    assertTrue(BridgeVersion.isAtLeast("0.11.0", "0.11.0"));
    assertFalse(BridgeVersion.isAtLeast("0.9.0", "0.11.0"));
    assertTrue(BridgeVersion.isAtLeast("1.0.0", "0.99.99"));
  }

  @Test
  void ignoresALeadingVAndASuffix() {
    assertTrue(BridgeVersion.isAtLeast("v0.12.0", "0.11.0"));
    assertTrue(BridgeVersion.isAtLeast("0.13.0-rc1", "0.13.0"));
  }

  @Test
  void aVersionThatCannotBeReadIsNeverCurrent() {
    assertFalse(BridgeVersion.isAtLeast(null, "0.11.0"));
    assertFalse(BridgeVersion.isAtLeast("dev", "0.11.0"));
    assertFalse(BridgeVersion.isAtLeast("0.12", "0.11.0"));
  }
}
