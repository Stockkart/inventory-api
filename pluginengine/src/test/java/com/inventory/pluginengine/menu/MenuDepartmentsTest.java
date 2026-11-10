package com.inventory.pluginengine.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MenuDepartmentsTest {

  @Test
  void nullFallsBackToKitchen() {
    assertEquals("KITCHEN", MenuDepartments.resolve(null));
  }

  @Test
  void blankFallsBackToKitchen() {
    assertEquals("KITCHEN", MenuDepartments.resolve("   "));
  }

  @Test
  void valueIsTrimmedAndUppercased() {
    assertEquals("BAR", MenuDepartments.resolve("  bar "));
  }

  @Test
  void resolutionIsStableSoGroupingIsConsistent() {
    assertEquals(MenuDepartments.resolve("Bar"), MenuDepartments.resolve("BAR "));
  }

  @Test
  void explicitNoneStaysNoneAndEmitsNoTicket() {
    assertEquals("NONE", MenuDepartments.resolve(" none "));
    assertFalse(MenuDepartments.emitsTicket("NONE"));
    assertTrue(MenuDepartments.emitsTicket(MenuDepartments.resolve(null)));
  }
}
