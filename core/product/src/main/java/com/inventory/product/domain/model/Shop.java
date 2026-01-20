package com.inventory.product.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "shops")
public class Shop {

  @Id
  private String shopId;
  private String name;
  private Location location;
  private String businessId;
  private String contactEmail;
  private String contactPhone;
  private String status;
  private boolean active;
  private Integer userLimit;
  private Integer userCount;
  private String initialAdminName;
  private String initialAdminEmail;
  private Instant createdAt;
  private Instant approvedAt;
  private String gstinNo; // Optional: GSTIN number
  private String fssai; // Optional: FSSAI license number
  private String dlNo; // Required for pharmacy/pharm business types
  private String panNo; // Optional: PAN number
  private String sgst; // Optional: State GST
  private String cgst; // Optional: Central GST
  private String tagline; // Optional: Shop tagline, banner word, or highlight text
}

