package com.inventory.product.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VendorPurchaseReturnSummaryDto {

  private String returnId;

  private String supplierCreditNoteNo;

  private String vendorPurchaseInvoiceId;

  /** Purchase invoice number at time of return (resolved from linked invoice). */
  private String invoiceNo;

  /** Resolved vendor display name when available. */
  private String vendorName;

  private BigDecimal returnAmount;

  private int totalLinesReturned;

  /** Returned lines with tax breakdown (base units per line). */
  private List<VendorPurchaseReturnLineSummaryDto> lines;

  private String reason;

  private Instant createdAt;
}
