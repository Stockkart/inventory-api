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
  private Boolean showShopName;
  private Boolean showShopAddress;
  private Boolean showShopTagline;
  private Boolean showShopPhone;
  private Boolean showShopEmail;
  private Boolean showShopGstin;
  private Boolean showShopPan;
  private Boolean showShopDlNo;
  private Boolean showShopFssai;

  private Boolean showBuyerDetails;
  private Boolean showCustomerName;
  private Boolean showCustomerAddress;
  private Boolean showCustomerPhone;
  private Boolean showCustomerEmail;
  private Boolean showCustomerGstin;
  private Boolean showCustomerPan;
  private Boolean showCustomerDlNo;

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
