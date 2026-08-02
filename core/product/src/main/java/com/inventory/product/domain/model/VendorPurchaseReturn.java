package com.inventory.product.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Purchase return raised against one vendor invoice; reduces inventory and feeds GSTR-2 CDNR/CDNUR.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "vendor_purchase_returns")
public class VendorPurchaseReturn {

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
  private String userId;

  /** {@link VendorPurchaseInvoice} document id */
  private String vendorPurchaseInvoiceId;

  /** Inward-facing credit-note reference we assign (supplier CN); e.g. VCN-00001 */
  private String supplierCreditNoteNo;

  private List<VendorPurchaseReturnItem> items;

  /** Total note value incl. tax parts we track (≈ taxable + CGST + SGST). */
  private BigDecimal returnAmount;

  private String paymentMethod;
  private BigDecimal refundCash;
  private BigDecimal refundOnline;
  private BigDecimal refundToCredit;

  private Integer totalLinesReturned;

  private String reason;

  private Instant createdAt;
  private Instant updatedAt;
}
