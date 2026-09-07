package com.inventory.product.rest.dto.request;

import com.inventory.common.tax.PurchaseTaxTreatment;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Corrections to a purchase invoice's header, keyed from the paper bill.
 *
 * <p>Only the money on the header and how it should be read. The lines are not amendable here:
 * they are what the stock was created from, and changing a quantity or a cost after the fact
 * would leave the invoice describing goods the shop never received. A line that is genuinely
 * wrong is a stock correction, which is a different operation with its own trail.
 *
 * <p>Every field is optional. A null field is left as it stands, so an operator adding the totals
 * to a bill that never had them does not have to restate the payment method to do it.
 */
@Data
public class AmendVendorPurchaseInvoiceRequest {

  private BigDecimal lineSubTotal;
  private BigDecimal taxTotal;
  private BigDecimal shippingCharge;
  private BigDecimal otherCharges;
  /** Bill-level discount in currency units (not %). */
  private BigDecimal overallDiscount;
  private BigDecimal roundOff;
  private BigDecimal invoiceTotal;
  /** Whether the line amounts on this bill already include GST. */
  private PurchaseTaxTreatment taxTreatment;

  /**
   * Why the invoice is being changed.
   *
   * <p>Required. An amendment to a filed figure that no one can account for is worse than the
   * wrong figure, because the wrong figure at least has a bill behind it.
   */
  private String reason;
}
