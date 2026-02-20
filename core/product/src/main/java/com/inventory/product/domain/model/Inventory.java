package com.inventory.product.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;

@Data
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
  private Integer thresholdCount;
  private Instant receivedDate;
  private Instant expiryDate;
  private String shopId;
  private String userId;
  private String vendorId;
  private BigDecimal maximumRetailPrice;
  private BigDecimal costPrice;
  private BigDecimal sellingPrice;
  private String pricingId;
  private BigDecimal additionalDiscount; // Optional: Additional discount amount
  private String hsn; // Optional: HSN code
  private String batchNo; // Optional: Batch number
  private Integer scheme; // Optional: Free units (e.g. 20 = 20 free). Total received = count + scheme.
  private String sgst; // Optional: State GST rate (e.g., "9" for 9%). Uses shop default if not provided.
  private String cgst; // Optional: Central GST rate (e.g., "9" for 9%). Uses shop default if not provided.
  private Instant createdAt;
  private Instant updatedAt;
}

