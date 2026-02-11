package com.inventory.product.rest.dto.inventory;

import com.inventory.notifications.rest.dto.CustomReminderRequest;
import com.inventory.product.domain.model.DiscountApplicable;
import com.inventory.product.domain.model.ItemType;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Data
public class CreateInventoryRequest {
  private String barcode;
  private String name;
  private String description;
  private String companyName;
  private BigDecimal maximumRetailPrice;
  private BigDecimal costPrice;
  private BigDecimal sellingPrice;
  private BigDecimal additionalDiscount; // Optional: Additional discount amount
  private String businessType;
  private String location;
  private ItemType itemType;
  /** When itemType is DEGREE, e.g. 8 for "8 deg", 24 for "24 deg" */
  private Integer itemTypeDegree;
  private DiscountApplicable discountApplicable;
  /** Purchase date (when bought from vendor). Optional; defaults to received date if not set. */
  private Instant purchaseDate;
  private Integer count;
  private Integer thresholdCount;
  private Instant expiryDate;
  private Instant reminderAt;
  private List<CustomReminderRequest> customReminders;
  // Vendor ID (optional - reference to existing vendor)
  private String vendorId;
  // Lot ID (optional - if provided, will reuse existing lot; if not, will generate new one)
  private String lotId;
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

