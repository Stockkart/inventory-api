package com.inventory.product.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.rest.dto.request.VendorPurchaseInvoiceRequest;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class VendorPurchaseInvoiceValidatorTest {

  private final VendorPurchaseInvoiceValidator validator = new VendorPurchaseInvoiceValidator();

  @Test
  void anEmptyHeaderIsAccepted() {
    assertDoesNotThrow(() -> validator.validateHeader(new VendorPurchaseInvoiceRequest()));
    assertDoesNotThrow(() -> validator.validateHeader(null));
  }

  @Test
  void aNegativeAmountIsRefused() {
    VendorPurchaseInvoiceRequest request = new VendorPurchaseInvoiceRequest();
    request.setShippingCharge(new BigDecimal("-10"));

    ValidationException e =
        assertThrows(ValidationException.class, () -> validator.validateHeader(request));

    assertTrue(e.getMessage().contains("Shipping charge cannot be negative"), e.getMessage());
  }

  @Test
  void aNegativeSentTotalIsRefused() {
    VendorPurchaseInvoiceRequest request = new VendorPurchaseInvoiceRequest();
    request.setTaxTotal(new BigDecimal("-1"));

    ValidationException e =
        assertThrows(ValidationException.class, () -> validator.validateHeader(request));

    assertTrue(e.getMessage().contains("Tax total cannot be negative"), e.getMessage());
  }

  @Test
  void theAmendPathRefusesTheSameThings() {
    assertThrows(ValidationException.class, () -> validator.validateHeaderAmounts(
        null, null, new BigDecimal("-1")));
  }
}
