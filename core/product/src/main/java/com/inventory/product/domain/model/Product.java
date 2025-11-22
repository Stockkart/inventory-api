package com.inventory.product.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "products")
public class Product {

  @Id
  private String barcode;
  private String prefix;
  private String companyCode;
  private String productTypeCode;
  private String name;
  private String model;
  private BigDecimal price;
  private String businessId;
  private String shopId;
  private String userId;
  private String businessType;
  private Instant createdAt;
  private Instant updatedAt;
}

