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
  private String invoiceNo;
  private Instant invoiceDate;
  private BigDecimal invoiceTotal;
  private int lineCount;
  private Instant createdAt;
  private Boolean synthetic;
  private String legacyLotId;
}
