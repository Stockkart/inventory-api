package com.inventory.hr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class WorkerModulesTest {

  @Test
  void everyShopGetsAttendanceHistoryProfileInThatOrder() {
    assertEquals(List.of("ATTENDANCE", "HISTORY", "PROFILE"), WorkerModules.forShop("shop-1"));
    assertEquals(WorkerModules.forShop("shop-1"), WorkerModules.forShop("shop-2"));
    assertEquals(WorkerModules.DEFAULT, WorkerModules.forShop(null));
  }

  @Test
  void laterModulesAreNotListedYet() {
    List<String> modules = WorkerModules.forShop("shop-1");
    for (String later : List.of("PAYSLIPS", "LEAVE", "ADVANCES")) {
      assertEquals(false, modules.contains(later), later);
    }
    assertEquals(modules.size(), modules.stream().distinct().count());
  }

  @Test
  void theListCannotBeChangedByACaller() {
    assertThrows(UnsupportedOperationException.class, () -> WorkerModules.forShop("s").add("X"));
  }
}
