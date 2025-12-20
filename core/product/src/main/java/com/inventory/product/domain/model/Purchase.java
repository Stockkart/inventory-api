package com.inventory.product.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "purchases")
public class Purchase {

  @Id
  private String id;
  private String invoiceId;
  private String invoiceNo;
  private String businessType;
  private String userId;
  private String shopId;
  private List<PurchaseItem> items;
  private BigDecimal subTotal;
  private BigDecimal taxTotal;
  private BigDecimal discountTotal;
  private BigDecimal grandTotal;
  private Instant soldAt;
  private boolean valid;
  private String paymentMethod;
  private PurchaseStatus status;
  private String customerId;
  private String customerName; // Used when only name is provided without phone
  private Instant createdAt;
  private Instant updatedAt;
}

