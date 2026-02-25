package com.inventory.product.rest.dto.inventory;

import com.inventory.reminders.rest.dto.CustomReminderRequest;
import com.inventory.pricing.api.dto.RateDto;
import com.inventory.product.domain.model.DiscountApplicable;
import com.inventory.product.domain.model.ItemType;
import com.inventory.product.domain.model.SchemeType;
import com.inventory.product.domain.model.UnitConversion;
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
  private BigDecimal priceToRetail;
  private List<RateDto> rates;
  private String defaultRate;
  private BigDecimal additionalDiscount; // Optional: Additional discount amount
  private String businessType;
  private String location;
  private ItemType itemType;
  private Integer itemTypeDegree;
  private DiscountApplicable discountApplicable;
  private Instant purchaseDate;
  private Integer count;
  /** Base stock unit (e.g. TAB, ML, BOTTLE). Defaults to UNIT when omitted. */
  private String baseUnit;
  /** Optional conversion where factor is base units in 1 sale/display unit. */
  private UnitConversion unitConversions;
  private Integer thresholdCount;
  private Instant expiryDate;
  private Instant reminderAt;
  private List<CustomReminderRequest> customReminders;
  // HSN code (optional)
  private String hsn;
  // Batch number (optional)
  private String batchNo;
  private SchemeType schemeType;
  private Integer scheme;
  private BigDecimal schemePercentage;
  // SGST rate (optional, e.g., "9" for 9%). Uses shop default if not provided.
  private String sgst;
  // CGST rate (optional, e.g., "9" for 9%). Uses shop default if not provided.
  private String cgst;
}

