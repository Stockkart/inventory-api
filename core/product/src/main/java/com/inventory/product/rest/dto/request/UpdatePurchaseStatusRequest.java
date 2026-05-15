package com.inventory.product.rest.dto.request;

import com.inventory.product.domain.model.enums.PurchaseStatus;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class UpdatePurchaseStatusRequest {
  private String purchaseId;
  private PurchaseStatus status; // PENDING or COMPLETED
  private String paymentMethod; // Optional, defaults to "CASH" if not provided
  /** Cash collected at checkout (split-aware). */
  private BigDecimal cashAmount;
  /** Online / UPI / card collected at checkout (split-aware). */
  private BigDecimal onlineAmount;
  /** Amount posted to customer credit ledger (split-aware). */
  private BigDecimal creditAmount;
  /** Legacy paid-now on credit sales (cash + online); prefer explicit split fields. */
  private BigDecimal creditPaidAmount;
}

