package com.inventory.product.rest.dto.response;

import com.inventory.product.domain.model.enums.PurchaseStatus;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuotationSummaryDto {
  private String purchaseId;
  private PurchaseStatus status;
  private String customerId;
  private String customerName;
  private String customerPhone;
  private String tokenNo;
  private int itemCount;
  private BigDecimal grandTotal;
  private Instant createdAt;
  private Instant updatedAt;
}
