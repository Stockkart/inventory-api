package com.inventory.product.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.rest.dto.request.PurchaseTaxPreviewRequest;
import com.inventory.product.rest.dto.request.VendorPurchaseInvoiceRequest;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Rejects a supplier bill header that cannot describe a real bill: a negative charge or discount.
 *
 * <p>The line subtotal, tax total and invoice total are worked out from the lines, so there is
 * nothing in them to check. Used when an invoice is registered and when its header is corrected,
 * so both paths refuse the same things.
 */
@Component
public class VendorPurchaseInvoiceValidator {

  /** The header as it arrives with a stock-in. */
  public void validateHeader(VendorPurchaseInvoiceRequest request) {
    if (request == null) {
      return;
    }
    validateHeaderAmounts(
        request.getShippingCharge(), request.getOtherCharges(), request.getOverallDiscount());
  }

  /** The typed header amounts on their own; any of them may be absent. */
  public void validateHeaderAmounts(
      BigDecimal shippingCharge, BigDecimal otherCharges, BigDecimal overallDiscount) {
    Set<String> errors = new LinkedHashSet<>();
    rejectIfNegative(errors, "Shipping charge", shippingCharge);
    rejectIfNegative(errors, "Other charges", otherCharges);
    rejectIfNegative(errors, "Overall discount", overallDiscount);
    if (!errors.isEmpty()) {
      throw new ValidationException(errors);
    }
  }

  private static void rejectIfNegative(Set<String> errors, String label, BigDecimal value) {
    if (value != null && value.signum() < 0) {
      errors.add(label + " cannot be negative");
    }
  }

  /** A preview needs at least one item row; everything else is optional while typing. */
  public void validatePreview(PurchaseTaxPreviewRequest request) {
    if (request == null || request.getItems() == null || request.getItems().isEmpty()) {
      throw new ValidationException("At least one product is needed to preview the bill");
    }
  }
}
