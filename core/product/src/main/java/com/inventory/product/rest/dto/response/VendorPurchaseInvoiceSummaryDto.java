package com.inventory.product.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VendorPurchaseInvoiceSummaryDto {
  private String id;
  private String vendorId;
  /** Resolved from {@link com.inventory.user.domain.model.Vendor}; null if missing or deleted */
  private String vendorName;
  private String invoiceNo;
  private Instant invoiceDate;
  private BigDecimal invoiceTotal;
  private String paymentMethod;
  private BigDecimal paidAmount;
  private int lineCount;
  private Instant createdAt;
  private Boolean synthetic;
  private String legacyLotId;
}
