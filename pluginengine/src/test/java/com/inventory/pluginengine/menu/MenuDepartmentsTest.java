package com.inventory.pluginengine.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
