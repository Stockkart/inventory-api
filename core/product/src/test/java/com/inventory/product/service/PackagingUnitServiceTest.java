package com.inventory.product.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.common.exception.ValidationException;
import org.junit.jupiter.api.Test;

class PackagingUnitServiceTest {

  private final PackagingUnitService service = new PackagingUnitService();

  @Test
  void packOfPacksIsRejectedInsteadOfDropped() {
    ValidationException e =
        assertThrows(ValidationException.class, () -> service.validateUnitsPerPack("PAC", 20));
    assertTrue(e.getMessage().contains("PAC is already a pack"));
    assertNull(service.buildUnitConversion("PAC", 20));
  }

  @Test
  void selfPackUnitsAcceptSinglePack() {
    assertDoesNotThrow(() -> service.validateUnitsPerPack("PAC", null));
    assertDoesNotThrow(() -> service.validateUnitsPerPack("PAC", 1));
    assertDoesNotThrow(() -> service.validateUnitsPerPack("BOX", 0));
  }

  @Test
  void packOnlySelfPackUnitNoLongerRequiresDiscardedFactor() {
    assertDoesNotThrow(() -> service.validateUnitsPerPack("BTL", null));
    assertThrows(ValidationException.class, () -> service.validateUnitsPerPack("BTL", 90));
  }

  @Test
  void unitsWithSeparatePackKeepTheirFactor() {
    assertDoesNotThrow(() -> service.validateUnitsPerPack("PCS", 20));
    assertEquals(20, service.buildUnitConversion("PCS", 20).getFactor());
    assertThrows(ValidationException.class, () -> service.validateUnitsPerPack("MLT", null));
  }
}
