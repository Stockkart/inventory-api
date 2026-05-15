package com.inventory.product.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
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
