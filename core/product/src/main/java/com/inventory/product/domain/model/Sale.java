package com.inventory.product.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "sales")
public class Sale {

  @Id
  private String id;
  private String invoiceId;
  private String invoiceNo;
  private String productName;
  private String userId;
  private String shopId;
  private List<SaleItem> items;
  private BigDecimal subTotal;
  private BigDecimal taxTotal;
  private BigDecimal discountTotal;
  private BigDecimal grandTotal;
  private Instant soldAt;
  private boolean valid;
  private String paymentMethod;
}

