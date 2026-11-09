package com.inventory.product.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.rest.dto.request.UpdateInventoryRequest;
import com.inventory.product.service.PackagingUnitService;
import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** clearFields names only clearable fields, and never one the same request also sets. */
class InventoryValidatorClearFieldsTest {

  private final InventoryValidator validator = new InventoryValidator(mock(PackagingUnitService.class));

  private static UpdateInventoryRequest clearing(String... fields) {
    UpdateInventoryRequest r = new UpdateInventoryRequest();
    r.setClearFields(Set.of(fields));
    return r;
  }

  @Test
  void lotAndPricingFieldsMayBeCleared() {
    assertDoesNotThrow(() -> validator.validateUpdateRequest(clearing(
        "barcode", "description", "companyName", "location", "hsn", "discountApplicable",
        "thresholdCount", "saleAdditionalDiscount", "purchaseAdditionalDiscount", "saleScheme",
        "purchaseScheme")));
  }

  @Test
  void theNameCannotBeCleared() {
    ValidationException e = assertThrows(ValidationException.class,
        () -> validator.validateUpdateRequest(clearing("name")));
    assertEquals("Field cannot be cleared: name", e.getMessage());
  }

  @Test
  void aFieldCannotBeSetAndClearedTogether() {
    UpdateInventoryRequest r = clearing("saleAdditionalDiscount");
    r.setSaleAdditionalDiscount(new BigDecimal("3"));

    ValidationException e = assertThrows(ValidationException.class, () -> validator.validateUpdateRequest(r));
    assertEquals("Field is both set and cleared: saleAdditionalDiscount", e.getMessage());
  }

  @Test
  void aSchemeCannotBeSetAndClearedTogether() {
    UpdateInventoryRequest r = clearing("purchaseScheme");
    r.setPurchaseSchemePayFor(10);
    r.setPurchaseSchemeFree(2);

    assertThrows(ValidationException.class, () -> validator.validateUpdateRequest(r));
  }
}
