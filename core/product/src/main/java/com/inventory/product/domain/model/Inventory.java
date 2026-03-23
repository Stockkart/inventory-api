package com.inventory.product.domain.model;

import com.inventory.product.domain.model.enums.BillingMode;
import com.inventory.product.domain.model.enums.DiscountApplicable;
import com.inventory.product.domain.model.enums.ItemType;
import com.inventory.product.domain.model.enums.SchemeType;
import com.inventory.pricing.rest.dto.response.RateDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

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
  /** Display received count (conversion unit if configured, else baseUnit). */
  private BigDecimal receivedCount;
  /** Display sold count (conversion unit if configured, else baseUnit). */
  private BigDecimal soldCount;
  /** Display current count (conversion unit if configured, else baseUnit). */
  private BigDecimal currentCount;
  /** Canonical received quantity stored in baseUnit. */
  private Integer receivedBaseCount;
  /** Canonical sold quantity stored in baseUnit. */
  private Integer soldBaseCount;
  /** Canonical current quantity stored in baseUnit. */
  private Integer currentBaseCount;
  /** Base stock unit (e.g. TAB, ML, BOTTLE). Counts are stored in this unit. */
  private String baseUnit;
  /** Optional conversion where factor is base units in 1 sale/display unit. */
  private UnitConversion unitConversions;
  private Integer thresholdCount;
  private Instant receivedDate;
  private Instant expiryDate;
  private String shopId;
  private String userId;
  private String vendorId;
  /** Reference to Pricing document (faster lookup by _id). Null for legacy inventories. */
  private String pricingId;
  private String hsn;
  private String batchNo;
  private BillingMode billingMode;
  private SchemeType schemeType; // FIXED_UNITS (default/backward) or PERCENTAGE
  /** @deprecated Prefer schemePayFor + schemeFree. When set: legacy "free units" only; total received = count + scheme. */
  private Integer scheme;
  /** When schemeType FIXED_UNITS: pay for this many (e.g. 10). With schemeFree = "schemeFree free on schemePayFor". */
  private Integer schemePayFor;
  /** When schemeType FIXED_UNITS: free units per batch (e.g. 2). With schemePayFor=10 = "2 free on 10". */
  private Integer schemeFree;
  private BigDecimal schemePercentage; // When schemeType PERCENTAGE: e.g. 10 = 10% extra free.
  /** Purchase (from vendor) - for comparison at sale. Read-only during sale. */
  private SchemeType purchaseSchemeType;
  private Integer purchaseSchemePayFor;
  private Integer purchaseSchemeFree;
  private BigDecimal purchaseSchemePercentage;
  /** Transient: populated from Pricing module on read via AOP; not persisted. */
  @Transient
  private BigDecimal maximumRetailPrice;
  @Transient
  private BigDecimal costPrice;
  /** Original Price to Retail (PTR). Immutable base from pricing. */
  @Transient
  private BigDecimal priceToRetail;
  /** Effective selling price (from defaultRate). Used for sales. */
  @Transient
  private BigDecimal sellingPrice;
  @Transient
  private List<RateDto> rates;
  @Transient
  private String defaultRate;
  @Transient
  private BigDecimal additionalDiscount;
  /** Purchase add. discount from Pricing. Populated on read. */
  @Transient
  private BigDecimal purchaseAdditionalDiscount;
  @Transient
  private String sgst;
  @Transient
  private String cgst;
  private Instant createdAt;
  private Instant updatedAt;
}

