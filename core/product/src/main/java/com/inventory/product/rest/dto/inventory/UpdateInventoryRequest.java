package com.inventory.product.rest.dto.inventory;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.inventory.pricing.rest.dto.RateDto;
import com.inventory.product.rest.dto.FlexibleInstantDeserializer;
import com.inventory.product.domain.model.BillingMode;
import com.inventory.product.domain.model.DiscountApplicable;
import com.inventory.product.domain.model.ItemType;
import com.inventory.product.domain.model.SchemeType;
import com.inventory.product.domain.model.UnitConversion;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Request for partial update of inventory.
 * Only provided (non-null) fields are updated; omitted fields retain existing values.
 */
@Data
public class UpdateInventoryRequest {
  // Product details
  private String barcode;
  private String name;
  private String description;
  private String companyName;
  private String businessType;
  private String location;

  // Pricing (persisted in Pricing module; inventory has transient reference)
  private BigDecimal maximumRetailPrice;
  private BigDecimal costPrice;
  private BigDecimal priceToRetail;
  private List<RateDto> rates;
  private String defaultRate;
  private BigDecimal additionalDiscount;
  private String sgst;
  private String cgst;

  // Inventory attributes
  private ItemType itemType;
  private Integer itemTypeDegree;
  private DiscountApplicable discountApplicable;
  @JsonDeserialize(using = FlexibleInstantDeserializer.class)
  private Instant purchaseDate;
  @JsonDeserialize(using = FlexibleInstantDeserializer.class)
  private Instant expiryDate;
  private String hsn;
  private String batchNo;
  private String vendorId;
  private BillingMode billingMode;

  // Scheme
  private SchemeType schemeType;
  private Integer scheme;
  private BigDecimal schemePercentage;

  // Units and stock
  private String baseUnit;
  private UnitConversion unitConversions;
  private Integer thresholdCount;
}

