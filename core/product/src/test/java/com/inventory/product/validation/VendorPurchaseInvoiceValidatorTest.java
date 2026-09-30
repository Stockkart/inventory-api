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

  private static VendorPurchaseInvoiceRequest header(String subTotal, String tax) {
    VendorPurchaseInvoiceRequest request = new VendorPurchaseInvoiceRequest();
    request.setLineSubTotal(subTotal == null ? null : new BigDecimal(subTotal));
    request.setTaxTotal(tax == null ? null : new BigDecimal(tax));
    return request;
  }

  @Test
  void aHeaderThatDisagreesWithItsLinesIsStillAccepted() {
    assertDoesNotThrow(() -> validator.validateHeader(header("1000.00", "999.00")));
    assertDoesNotThrow(() -> validator.validateHeader(header(null, null)));
    assertDoesNotThrow(() -> validator.validateHeader(null));
  }

  @Test
  void aNegativeAmountIsRefused() {
    VendorPurchaseInvoiceRequest request = header("1000.00", "50.00");
    request.setShippingCharge(new BigDecimal("-10"));

    ValidationException e =
        assertThrows(ValidationException.class, () -> validator.validateHeader(request));

    assertTrue(e.getMessage().contains("Shipping charge cannot be negative"), e.getMessage());
  }

  @Test
  void taxAboveTheSubtotalIsRefused() {
    ValidationException e = assertThrows(
        ValidationException.class, () -> validator.validateHeader(header("100.00", "100.01")));

    assertTrue(e.getMessage().contains("cannot exceed the line subtotal"), e.getMessage());
  }

  @Test
  void theAmendPathRefusesTheSameThings() {
    assertThrows(ValidationException.class, () -> validator.validateHeaderAmounts(
        new BigDecimal("-1"), null, null, null, null, null));
    assertThrows(ValidationException.class, () -> validator.validateHeaderAmounts(
        new BigDecimal("100"), new BigDecimal("200"), null, null, null, null));
  }
}
