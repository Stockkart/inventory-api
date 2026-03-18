package com.inventory.product.rest.dto.request;

import com.inventory.product.domain.model.enums.PurchaseStatus;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class UpdatePurchaseStatusRequest {
  private String purchaseId;
  private PurchaseStatus status; // PENDING or COMPLETED
  /**
   * CASH, ONLINE, CREDIT, SPLIT (credit + cash or online), MULTI (cash + online).
   */
  private String paymentMethod;
  /** Required for SPLIT and MULTI when completing; optional for CASH/ONLINE/CREDIT (inferred). */
  private BigDecimal amountPaidCash;
  private BigDecimal amountPaidOnline;
  /** Credit portion; required for SPLIT; for CREDIT inferred as full total if omitted. */
  private BigDecimal amountOnCredit;
}

