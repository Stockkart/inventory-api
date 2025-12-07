package com.inventory.product.rest.dto.sale;

import com.inventory.product.domain.model.PurchaseStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseSummaryDto {
  private String purchaseId;
  private String invoiceId;
  private String invoiceNo;
  private String businessType;
  private String userId;
  private String shopId;
  private BigDecimal grandTotal;
  private Instant soldAt;
  private PurchaseStatus status;
  private String paymentMethod;
  private String customerName;
  private String customerAddress;
  private String customerPhone;
}

