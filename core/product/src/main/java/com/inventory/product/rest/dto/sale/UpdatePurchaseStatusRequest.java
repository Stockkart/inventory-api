package com.inventory.product.rest.dto.sale;

import com.inventory.product.domain.model.PurchaseStatus;
import lombok.Data;

@Data
public class UpdatePurchaseStatusRequest {
  private String purchaseId;
  private PurchaseStatus status; // PENDING or COMPLETED
  private String paymentMethod; // Optional, defaults to "CASH" if not provided
}

