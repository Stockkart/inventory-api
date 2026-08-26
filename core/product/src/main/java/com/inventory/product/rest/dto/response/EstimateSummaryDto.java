package com.inventory.product.rest.dto.response;

import com.inventory.product.domain.model.enums.BillingMode;
import com.inventory.product.domain.model.enums.EstimateState;
import com.inventory.product.domain.model.enums.PurchaseStatus;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EstimateSummaryDto {
  private String purchaseId;
  private String estimateNo;
  private PurchaseStatus status;
  private EstimateState estimateState;
  private BillingMode billingMode;
  private String customerId;
  private String customerName;
  private String customerPhone;
  private String customerEmail;
  private int itemCount;
  private BigDecimal grandTotal;
  private String convertedToPurchaseId;
  private Instant createdAt;
  private Instant updatedAt;
}
