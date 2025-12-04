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
@Document(collection = "inventory")
public class Inventory {

  @Id
  private String id;
  private String lotId;
  private String barcode;
  private String name;
  private String description;
  private String companyName;
  private String businessType;
  private String location;
  private Integer receivedCount;
  private Integer soldCount;
  private Integer currentCount;
  private Instant receivedDate;
  private Instant expiryDate;
  private String shopId;
  private String userId;
  private BigDecimal maximumRetailPrice;
  private BigDecimal costPrice;
  private BigDecimal sellingPrice;
  private Instant createdAt;
  private Instant updatedAt;
}

