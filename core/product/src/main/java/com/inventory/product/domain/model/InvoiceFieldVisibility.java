package com.inventory.product.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-section / per-column visibility for sale invoice PDFs.
 * Null booleans mean "use built-in default for the billing mode".
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceFieldVisibility {

  private Boolean showSellerDetails;
  private Boolean showBuyerDetails;
  private Boolean showPaymentMethod;
  private Boolean showTaxDetails;
  private Boolean showAmountInWords;
  private Boolean showAmountSaved;
  private Boolean showAdditionalDiscount;
  private Boolean showHsn;
  private Boolean showMfg;
  private Boolean showExpiry;
  private Boolean showBatch;
  private Boolean showMrp;
  private Boolean showScheme;
  private Boolean showLineDiscount;
  private Boolean showSignatures;
}
