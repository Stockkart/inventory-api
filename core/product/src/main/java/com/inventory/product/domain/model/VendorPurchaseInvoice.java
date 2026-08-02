package com.inventory.product.domain.model;

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

  /**
   * Stable business transaction identifier (UUID v4). Distinct from {@link #id}, which is the Mongo
   * storage id. The index is declared here as the canonical schema constraint, but is created
   * operationally — {@code spring.data.mongodb.auto-index-creation} is intentionally disabled, so
   * this annotation has no runtime effect. See the design spec, section 1.
   */
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
  /** Amount paid in cash at stock-in (split tender). */
  private BigDecimal cashAmount;
  /** Amount paid online at stock-in (split tender). */
  private BigDecimal onlineAmount;
  /** Amount posted to vendor credit / payable at stock-in (split tender). */
  private BigDecimal creditAmount;
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
