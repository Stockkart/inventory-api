package com.inventory.product.domain.model;

import com.inventory.product.tax.PurchaseTaxTreatment;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "vendor_purchase_invoices")
@CompoundIndex(
    name = "uniq_shop_vendor_invoice_no",
    def = "{'shopId': 1, 'vendorId': 1, 'invoiceNo': 1}",
    unique = true)
public class VendorPurchaseInvoice {

  @Id
  private String id;
  /** System-wide unique money transaction id (UUID). */
  @Indexed(unique = true, sparse = true)
  private String txnId;
  private String shopId;
  private String vendorId;
  private String invoiceNo;
  private Instant invoiceDate;
  private BigDecimal lineSubTotal;
  private BigDecimal taxTotal;
  private BigDecimal shippingCharge;
  private BigDecimal otherCharges;
  /** Bill-level discount (₹); reduces payable total and capitalized goods value. */
  private BigDecimal overallDiscount;
  private BigDecimal roundOff;
  private BigDecimal invoiceTotal;
  private String paymentMethod;
  private BigDecimal paidAmount;
  /**
   * Whether the line amounts on this bill already contain GST.
   *
   * <p>Null on every invoice recorded before the distinction was captured, and read as
   * {@code EXCLUSIVE} — the assumption those documents were written under, so their reported
   * figures do not move.
   */
  private PurchaseTaxTreatment taxTreatment;

  // --- What the lines say, kept beside what the operator typed ---------------
  // The stated header above is never overwritten: it is what the person holding
  // the bill read off it, and a machine that quietly replaces it destroys the
  // one record of what the paper said. These are the second opinion, and the
  // verdict is how far the two agree.

  /** Taxable value the lines come to once resolved. */
  private BigDecimal computedLineSubTotal;
  /** Tax the lines come to at their own rates. */
  private BigDecimal computedTaxTotal;
  /** OK | MISSING | MISMATCH | RATE_CONFLICT -- see {@code PurchaseTaxBasis.Verdict}. */
  private String headerReconciliation;
  /**
   * True when invoice number was generated (AUTO-*) because the user did not enter one.
   * User-entered invoices are non-synthetic.
   */
  private Boolean synthetic;
  /** Former inventory lot id (LOT-*) after data migration, for support only. */
  private String legacyLotId;
  private List<VendorPurchaseInvoiceLine> lines = new ArrayList<>();
  private Instant createdAt;
  private String createdByUserId;
}
