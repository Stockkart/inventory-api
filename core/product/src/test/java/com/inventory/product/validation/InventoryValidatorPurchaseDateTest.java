package com.inventory.product.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.rest.dto.request.CreateInventoryRequest;
import com.inventory.product.service.PackagingUnitService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

/**
 * A new stock-in must be dated within 30 days of today. After that the purchase date is fixed:
 * an edit may resend the stored day but never change it.
 */
class InventoryValidatorPurchaseDateTest {

  private static final Instant STORED = Instant.parse("2026-02-14T09:30:00Z");

  private final InventoryValidator validator = new InventoryValidator(mock(PackagingUnitService.class));

  private static CreateInventoryRequest create(Instant purchaseDate) {
    CreateInventoryRequest r = new CreateInventoryRequest();
    r.setName("Cephagraine drop");
    r.setCount(1);
    r.setPurchaseDate(purchaseDate);
    return r;
  }

  @Test
  void anEditWithoutADateIsAllowed() {
    assertDoesNotThrow(() -> validator.validatePurchaseDateUnchanged(null, STORED));
  }

  @Test
  void anEditMayResendTheStoredDay() {
    Instant sameDay = Instant.parse("2026-02-14T00:00:00Z");
    assertDoesNotThrow(() -> validator.validatePurchaseDateUnchanged(sameDay, STORED));
  }

  @Test
  void anEditCannotMoveTheDateBack() {
    Instant earlier = Instant.parse("2026-01-31T00:00:00Z");
    ValidationException e = assertThrows(
        ValidationException.class, () -> validator.validatePurchaseDateUnchanged(earlier, STORED));
    assertEquals(InventoryValidator.PURCHASE_DATE_NOT_EDITABLE, e.getMessage());
  }

  @Test
  void anEditCannotMoveTheDateForward() {
    Instant later = Instant.parse("2026-02-15T00:00:00Z");
    assertThrows(
        ValidationException.class, () -> validator.validatePurchaseDateUnchanged(later, STORED));
  }

  @Test
  void anEditCannotSetADateOnALotWithoutOne() {
    Instant any = Instant.parse("2026-02-14T00:00:00Z");
    assertThrows(ValidationException.class, () -> validator.validatePurchaseDateUnchanged(any, null));
  }

  @Test
  void aNewStockInStillKeepsTheThirtyDayWindow() {
    Instant longAgo = Instant.now().minus(31, ChronoUnit.DAYS);
    ValidationException e =
        assertThrows(ValidationException.class, () -> validator.validateCreateRequest(create(longAgo)));
    assertEquals("Purchase date cannot be more than 30 days in the past", e.getMessage());
  }
}
