package com.inventory.product.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "shops")
public class Shop {

  @Id
  private String shopId;
  private String name;
  private String location;
  private String businessId;
  private String contactEmail;
  private String status;
  private boolean active;
  private Integer userLimit;
  private Integer userCount;
  private String initialAdminName;
  private String initialAdminEmail;
  private Instant createdAt;
  private Instant approvedAt;
}

