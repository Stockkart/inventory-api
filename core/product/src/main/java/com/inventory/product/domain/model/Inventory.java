package com.inventory.product.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "inventory")
public class Inventory {

  @Id
  private String lotId;
  private String productId;
  private String location;
  private Integer receivedCount;
  private Integer soldCount;
  private Integer currentCount;
  private Instant receivedDate;
  private Instant expiryDate;
  private Map<String, Object> reminderConfig;
  private Instant createdAt;
  private Instant updatedAt;
  private String shopId;
  private String userId;
}

