package com.inventory.product.rest.dto.inventory;

import com.inventory.notifications.rest.dto.CustomReminderRequest;
import com.inventory.product.domain.model.Rate;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Request DTO for a single inventory item (without vendorId and lotId).
 * These fields are provided separately in BulkCreateInventoryRequest.
 */
@Data
public class CreateInventoryItemRequest {
  private String barcode;
  private String name;
  private String description;
  private String companyName;
  private BigDecimal maximumRetailPrice;
  private BigDecimal costPrice;
  private BigDecimal sellingPrice;
  private List<Rate> rates;
  private String defaultPrice;
  private BigDecimal additionalDiscount; // Optional: Additional discount amount
  private String businessType;
  private String location;
  private Integer count;
  private Integer thresholdCount;
  private Instant expiryDate;
  private Instant reminderAt;
  private List<CustomReminderRequest> customReminders;
  // HSN code (optional)
  private String hsn;
  // Batch number (optional)
  private String batchNo;
  // Scheme (optional): free units. Total stock = count + scheme.
  private Integer scheme;
  // SGST rate (optional, e.g., "9" for 9%). Uses shop default if not provided.
  private String sgst;
  // CGST rate (optional, e.g., "9" for 9%). Uses shop default if not provided.
  private String cgst;
}

