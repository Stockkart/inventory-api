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
  /** Reference to the shop-scoped {@link Product} catalog identity this lot belongs to. */
  private String productId;
  // --- Catalog identity: owned by Product; @Transient here, hydrated on read via
  // InventoryProductReadAspect. Not persisted on the inventory document. ---
  @Transient
  private String barcode;
  @Transient
  private String name;
  @Transient
  private String description;
  @Transient
  private String companyName;
  @Transient
  private String businessType;
  private String location;
  @Transient
  private ItemType itemType;
  /** When itemType is DEGREE, e.g. 8 for "8 deg", 24 for "24 deg" */
  @Transient
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
  /** Base stock unit (e.g. TAB, ML, BOTTLE). Counts are stored in this unit. Owned by Product. */
  @Transient
  private String baseUnit;
  /** Optional conversion where factor is base units in 1 sale/display unit. Owned by Product. */
  @Transient
  private UnitConversion unitConversions;
  private Integer thresholdCount;
  private Instant receivedDate;
  private Instant expiryDate;
  private String shopId;
  private String userId;
  private String vendorId;
  /** Vendor purchase invoice document id when stock-in was registered with invoice metadata. */
  private String vendorPurchaseInvoiceId;
  /** Reference to Pricing document (faster lookup by _id). Null for legacy inventories. */
  private String pricingId;
  /** Owned by Product; @Transient, hydrated on read. */
  @Transient
  private String hsn;
  private String batchNo;
  private BillingMode billingMode;
  /** Transient: from Pricing.saleScheme. Not persisted on inventory. */
  @Transient
  private SchemeType schemeType;
  /** Transient: legacy; populated from Pricing when applicable. */
  @Transient
  private Integer scheme;
  /** Transient: from Pricing.saleScheme. */
  @Transient
  private Integer schemePayFor;
  /** Transient: from Pricing.saleScheme. */
  @Transient
  private Integer schemeFree;
  /** Transient: from Pricing.saleScheme. */
  @Transient
  private BigDecimal schemePercentage;
  /** Transient: from Pricing.purchaseScheme. */
  @Transient
  private SchemeType purchaseSchemeType;
  /** Transient: from Pricing.purchaseScheme. */
  @Transient
  private Integer purchaseSchemePayFor;
  /** Transient: from Pricing.purchaseScheme. */
  @Transient
  private Integer purchaseSchemeFree;
  /** Transient: from Pricing.purchaseScheme. */
  @Transient
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
  private BigDecimal saleAdditionalDiscount;
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

