package com.inventory.product.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VendorPurchaseReturnResponse {

  private String returnId;
  private String supplierCreditNoteNo;
  private String vendorPurchaseInvoiceId;
  private BigDecimal returnAmount;
  private int totalLinesReturned;
  private Instant createdAt;
}
