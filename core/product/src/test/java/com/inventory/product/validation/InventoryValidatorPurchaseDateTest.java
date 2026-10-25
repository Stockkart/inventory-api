package com.inventory.product.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.rest.dto.request.CreateInventoryRequest;
import com.inventory.product.rest.dto.request.UpdateInventoryRequest;
import com.inventory.product.service.PackagingUnitService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

/**
 * A new stock-in must be dated within 30 days of today. Editing a lot corrects the date it was
 * bought, which can be any day in the past, but never a day still to come.
 */
class InventoryValidatorPurchaseDateTest {

  private final InventoryValidator validator = new InventoryValidator(mock(PackagingUnitService.class));

  private static UpdateInventoryRequest update(Instant purchaseDate) {
    UpdateInventoryRequest r = new UpdateInventoryRequest();
    r.setPurchaseDate(purchaseDate);
    return r;
  }

  private static CreateInventoryRequest create(Instant purchaseDate) {
    CreateInventoryRequest r = new CreateInventoryRequest();
    r.setName("Cephagraine drop");
    r.setCount(1);
    r.setPurchaseDate(purchaseDate);
    return r;
  }

  @Test
  void anEditMaySetADateMoreThanAYearBack() {
    Instant longAgo = Instant.parse("2025-01-31T00:00:00Z");
    assertDoesNotThrow(() -> validator.validateUpdateRequest(update(longAgo)));
  }

  @Test
  void anEditMaySetToday() {
    Instant today = Instant.now().truncatedTo(ChronoUnit.DAYS);
    assertDoesNotThrow(() -> validator.validateUpdateRequest(update(today)));
  }

  @Test
  void anEditCannotSetAFutureDay() {
    Instant tomorrow = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS);
    ValidationException e =
        assertThrows(ValidationException.class, () -> validator.validateUpdateRequest(update(tomorrow)));
    assertEquals("Purchase date cannot be in the future", e.getMessage());
  }

  @Test
  void aNewStockInStillKeepsTheThirtyDayWindow() {
    Instant longAgo = Instant.now().minus(31, ChronoUnit.DAYS);
    ValidationException e =
        assertThrows(ValidationException.class, () -> validator.validateCreateRequest(create(longAgo)));
    assertEquals("Purchase date cannot be more than 30 days in the past", e.getMessage());
  }
}
