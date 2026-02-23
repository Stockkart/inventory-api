package com.inventory.product.domain.model;

import com.inventory.pricing.api.dto.RateDto;
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
  private SchemeType schemeType; // FIXED_UNITS (default/backward) or PERCENTAGE
  private Integer scheme; // When schemeType FIXED_UNITS: free units. Total received = count + scheme.
  private BigDecimal schemePercentage; // When schemeType PERCENTAGE: e.g. 10 = 10% extra free.
  /** Transient: populated from Pricing module on read via AOP; not persisted. */
  @Transient
  private BigDecimal maximumRetailPrice;
  @Transient
  private BigDecimal costPrice;
  @Transient
  private BigDecimal priceToRetail;
  @Transient
  private List<RateDto> rates;
  @Transient
  private String defaultRate;
  @Transient
  private BigDecimal additionalDiscount;
  @Transient
  private String sgst;
  @Transient
  private String cgst;
  private Instant createdAt;
  private Instant updatedAt;
}

