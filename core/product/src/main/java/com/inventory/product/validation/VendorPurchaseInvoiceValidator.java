package com.inventory.product.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.rest.dto.request.VendorPurchaseInvoiceRequest;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Rejects a supplier bill header that cannot describe a real bill.
 *
 * <p>Deliberately narrow. A header that merely disagrees with its lines is recorded and flagged,
 * not refused: bills are entered daily with the goods already counted out, and stopping the
 * operator over a rupee would cost more than the rupee. What is refused is input no bill can
 * produce -- a negative amount, or tax exceeding the value it is charged on, which cannot happen
 * at any GST slab.
 *
 * <p>Used when an invoice is registered and when its header is corrected, so both paths refuse the
 * same things.
 */
@Component
public class VendorPurchaseInvoiceValidator {

  /** The header as it arrives with a stock-in. */
  public void validateHeader(VendorPurchaseInvoiceRequest request) {
    if (request == null) {
      return;
    }
    validateHeaderAmounts(
        request.getLineSubTotal(),
        request.getTaxTotal(),
        request.getInvoiceTotal(),
        request.getShippingCharge(),
        request.getOtherCharges(),
        request.getOverallDiscount());
  }

  /** The header amounts on their own; any of them may be absent. */
  public void validateHeaderAmounts(
      BigDecimal lineSubTotal,
      BigDecimal taxTotal,
      BigDecimal invoiceTotal,
      BigDecimal shippingCharge,
      BigDecimal otherCharges,
      BigDecimal overallDiscount) {
    Set<String> errors = new LinkedHashSet<>();
    rejectIfNegative(errors, "Line subtotal", lineSubTotal);
    rejectIfNegative(errors, "Tax total", taxTotal);
    rejectIfNegative(errors, "Invoice total", invoiceTotal);
    rejectIfNegative(errors, "Shipping charge", shippingCharge);
    rejectIfNegative(errors, "Other charges", otherCharges);
    rejectIfNegative(errors, "Overall discount", overallDiscount);

    if (lineSubTotal != null && taxTotal != null && lineSubTotal.signum() > 0
        && taxTotal.compareTo(lineSubTotal) > 0) {
      errors.add("Tax total (" + taxTotal + ") cannot exceed the line subtotal (" + lineSubTotal
          + ") -- the highest GST slab is 28%");
    }
    if (!errors.isEmpty()) {
      throw new ValidationException(errors);
    }
  }

  private static void rejectIfNegative(Set<String> errors, String label, BigDecimal value) {
    if (value != null && value.signum() < 0) {
      errors.add(label + " cannot be negative");
    }
  }
}
