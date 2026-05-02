package com.inventory.product.rest.dto.request;

import com.inventory.reminders.rest.dto.request.CustomReminderRequest;
import com.inventory.pricing.rest.dto.response.RateDto;
import com.inventory.product.domain.model.enums.DiscountApplicable;
import com.inventory.product.domain.model.enums.BillingMode;
import com.inventory.product.domain.model.enums.ItemType;
import com.inventory.product.domain.model.enums.SchemeType;
import com.inventory.product.domain.model.UnitConversion;
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
  private BigDecimal priceToRetail;
  private List<RateDto> rates;
  private String defaultRate;
  private BigDecimal saleAdditionalDiscount; // Optional: Additional discount amount
  private String businessType;
  private String location;
  private ItemType itemType;
  /** When itemType is DEGREE, e.g. 8 for "8 deg", 24 for "24 deg" */
  private Integer itemTypeDegree;
  private DiscountApplicable discountApplicable;
  /** Purchase date (when bought from vendor). Optional; defaults to received date if not set. */
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
  // Vendor ID (optional - reference to existing vendor)
  private String vendorId;
  // Lot ID (optional - if provided, will reuse existing lot; if not, will generate new one)
  private String lotId;
  /** Set when bulk registration includes vendor purchase invoice metadata. */
  private String vendorPurchaseInvoiceId;
  // HSN code (optional)
  private String hsn;
  // Batch number (optional)
  private String batchNo;
  // Billing mode for downstream sales: REGULAR or BASIC
  private BillingMode billingMode;
  // Scheme type: FIXED_UNITS (default) or PERCENTAGE
  private SchemeType schemeType;
  /** @deprecated Prefer schemePayFor + schemeFree. When used: total stock = count + scheme. */
  private Integer scheme;
  /** When schemeType FIXED_UNITS (new): pay for this many (e.g. 10). Quantity in payload = count only. */
  private Integer schemePayFor;
  /** When schemeType FIXED_UNITS (new): free units per batch (e.g. 2). "schemeFree free on schemePayFor". */
  private Integer schemeFree;
  // When schemeType PERCENTAGE: e.g. 10 = 10% extra free.
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
}

