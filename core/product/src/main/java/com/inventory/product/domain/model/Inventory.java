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
  private ItemType itemType;
  /** When itemType is DEGREE, e.g. 8 for "8 deg", 24 for "24 deg" */
  private Integer itemTypeDegree;
  private DiscountApplicable discountApplicable;
  /** Date when this inventory was purchased from vendor */
  private Instant purchaseDate;
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
  private BigDecimal additionalDiscount; // Optional: Additional discount amount
  private String hsn; // Optional: HSN code
  private String batchNo; // Optional: Batch number
  private SchemeType schemeType; // FIXED_UNITS (default/backward) or PERCENTAGE
  private Integer scheme; // When schemeType FIXED_UNITS: free units. Total received = count + scheme.
  private BigDecimal schemePercentage; // When schemeType PERCENTAGE: e.g. 10 = 10% extra free.
  private String sgst; // Optional: State GST rate (e.g., "9" for 9%). Uses shop default if not provided.
  private String cgst; // Optional: Central GST rate (e.g., "9" for 9%). Uses shop default if not provided.
  private Instant createdAt;
  private Instant updatedAt;
}

