package com.inventory.product.rest.dto.request;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.inventory.reminders.rest.dto.request.CustomReminderRequest;
import com.inventory.pricing.rest.dto.response.RateDto;
import com.inventory.product.domain.model.enums.DiscountApplicable;
import com.inventory.product.domain.model.enums.BillingMode;
import com.inventory.product.domain.model.enums.ItemType;
import com.inventory.product.domain.model.enums.SchemeType;
import com.inventory.product.domain.model.UnitConversion;
import com.inventory.common.jackson.FlexibleInstantDeserializer;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

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
  private BigDecimal sellingPrice;
  private List<RateDto> rates;
  private String defaultRate;
  private BigDecimal saleAdditionalDiscount; // Optional: Additional discount amount
  private String businessType;
  private String location;
  private ItemType itemType;
  private Integer itemTypeDegree;
  private DiscountApplicable discountApplicable;
  private Instant purchaseDate;
  private Integer count;
  /** GST UQC base unit (e.g. TBS, MLT, PCS). */
  private String baseUnit;
  /** Base units per pack (e.g. 50, 100). */
  private Integer unitsPerPack;
  /** Legacy pack conversion; prefer unitsPerPack. */
  private UnitConversion unitConversions;
  private Integer thresholdCount;
  @JsonDeserialize(using = FlexibleInstantDeserializer.class)
  private Instant expiryDate;
  @JsonDeserialize(using = FlexibleInstantDeserializer.class)
  private Instant reminderAt;
  private List<CustomReminderRequest> customReminders;
  // HSN code (optional)
  private String hsn;
  // Batch number (optional)
  private String batchNo;
  // Billing mode for downstream sales: REGULAR or BASIC
  private BillingMode billingMode;
  private SchemeType schemeType;
  /** @deprecated Prefer schemePayFor + schemeFree. */
  private Integer scheme;
  private Integer schemePayFor;
  private Integer schemeFree;
  private BigDecimal schemePercentage;
  /** Purchase (from vendor) - for comparison at sale. Read-only during sale. */
  private SchemeType purchaseSchemeType;
  private Integer purchaseSchemePayFor;
  private Integer purchaseSchemeFree;
  private BigDecimal purchaseSchemePercentage;
  private BigDecimal purchaseAdditionalDiscount;
  // SGST rate (optional, e.g., "9" for 9%). Uses shop default if not provided.
  private String sgst;
  // CGST rate (optional, e.g., "9" for 9%). Uses shop default if not provided.
  private String cgst;
  /** Vertical-specific fields (sport, brand, model, batchNo, expiryDate, …) keyed by schema field name. */
  private Map<String, Object> verticalFields;
}

