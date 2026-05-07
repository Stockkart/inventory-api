package com.inventory.product.rest.dto.request;

import com.inventory.product.domain.model.enums.PurchaseStatus;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class UpdatePurchaseStatusRequest {
  private String purchaseId;
  private PurchaseStatus status; // PENDING or COMPLETED
  private String paymentMethod; // Optional, defaults to "CASH" if not provided
  /** When completing a sale: GL asset to debit with the receipt (defaults to {@code CASH}). */
  private String receiptGlAccountCode;
  /** Optional paid-now amount for split credit sale (remaining goes to credit ledger). */
  private BigDecimal creditPaidAmount;
}

