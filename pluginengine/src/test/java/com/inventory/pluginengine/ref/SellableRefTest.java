package com.inventory.pluginengine.ref;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The ref grammar, which a menu portion now travels inside.
 *
 * <p>Two properties matter more than the round trip. A ref with no {@code @} must parse exactly
 * as it did before portions existed, or every purchase, invoice and reprint already in the
 * database reads back wrong. And a malformed ref must fail loudly: the failure mode a
 * {@code split("@")} would have -- taking element [1] of whatever came back -- is a cart line
 * priced as a portion the guest did not order.
 */
class SellableRefTest {

  @Test
  void aRefWithAVariantRoundTrips() {
    SellableRef ref = SellableRef.parse("menu:abc@half");

    assertEquals("menu", ref.kind());
    assertEquals("abc", ref.id());
    assertEquals("half", ref.variant());
    assertTrue(ref.hasVariant());
    assertEquals("menu:abc@half", ref.encode());
  }

  @Test
  void aRefWithoutAVariantParsesExactlyAsItAlwaysHas() {
    SellableRef ref = SellableRef.parse("menu:abc");

    assertEquals("menu", ref.kind());
    assertEquals("abc", ref.id());
    assertNull(ref.variant(), "no portion was named, and none may be invented");
    assertFalse(ref.hasVariant());
    assertEquals("menu:abc", ref.encode(), "and it re-encodes with no trailing '@'");
  }

  @Test
  void anInventoryRefIsUntouchedByAnyOfThis() {
    SellableRef ref = SellableRef.parse("inventory:lot-1");

    assertTrue(ref.isInventory());
    assertNull(ref.variant());
    assertEquals("inventory:lot-1", ref.encode());
  }

  @Test
  void theIdKeepsAnsweringForTheMenuLookupWhenAPortionIsNamed() {
    // The menu lookup takes id() and must not have to know portions exist.
    assertEquals("abc", SellableRef.parse("menu:abc@full").id());
  }

  @Test
  void aSecondAtSignIsRejectedRatherThanSplitOn() {
    IllegalArgumentException refused =
        assertThrows(IllegalArgumentException.class, () -> SellableRef.parse("menu:abc@a@b"));
    assertTrue(refused.getMessage().contains("menu:abc@a@b"), refused.getMessage());
  }

  @Test
  void anEmptyVariantIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> SellableRef.parse("menu:abc@"));
  }

  @Test
  void anEmptyIdIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> SellableRef.parse("menu:@half"));
  }

  @Test
  void aBlankOrMissingRefIsStillRejected() {
    assertThrows(IllegalArgumentException.class, () -> SellableRef.parse(null));
    assertThrows(IllegalArgumentException.class, () -> SellableRef.parse("   "));
    assertThrows(IllegalArgumentException.class, () -> SellableRef.parse("menu"));
    assertThrows(IllegalArgumentException.class, () -> SellableRef.parse(":abc"));
  }

  @Test
  void theLenientParseSwallowsAMalformedRefTheSameWayItAlwaysDid() {
    assertNull(SellableRef.parseLenient("menu:abc@a@b"));
    assertEquals("half", SellableRef.parseLenient("menu:abc@half").variant());
  }

  @Test
  void theTwoArgumentConstructorStillCompilesAndCarriesNoVariant() {
    SellableRef ref = new SellableRef("menu", "abc");

    assertNull(ref.variant());
    assertEquals("menu:abc", ref.encode());
    assertEquals(SellableRef.menu("abc"), ref);
  }

  @Test
  void aVariantCannotBeSmuggledPastTheConstructor() {
    assertThrows(IllegalArgumentException.class, () -> new SellableRef("menu", "abc", "a@b"));
    assertThrows(IllegalArgumentException.class, () -> new SellableRef("menu", "abc", "  "));
  }
}
