package com.inventory.product.domain.model;

import com.inventory.product.domain.model.enums.BillingMode;
import com.inventory.product.domain.model.enums.DiscountApplicable;
import com.inventory.product.domain.model.enums.ItemType;
import com.inventory.product.domain.model.enums.SchemeType;
import com.inventory.pricing.rest.dto.response.RateDto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One draft line on a stock-entry estimate. Mirrors {@code CreateInventoryItemRequest} so lock can
 * hand the payload to bulk create without a separate mapper surface.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockEntryEstimateLine {

  private String productId;
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
  private BigDecimal saleAdditionalDiscount;
  private String businessType;
  private String location;
  private ItemType itemType;
  private Integer itemTypeDegree;
  private DiscountApplicable discountApplicable;
  private Instant purchaseDate;
  private Integer count;
  private String baseUnit;
  private Integer unitsPerPack;
  private UnitConversion unitConversions;
  private Integer thresholdCount;
  private Instant expiryDate;
  private String hsn;
  private String batchNo;
  private BillingMode billingMode;
  private SchemeType schemeType;
  private Integer scheme;
  private Integer schemePayFor;
  private Integer schemeFree;
  private BigDecimal schemePercentage;
  private SchemeType purchaseSchemeType;
  private Integer purchaseSchemePayFor;
  private Integer purchaseSchemeFree;
  private BigDecimal purchaseSchemePercentage;
  private BigDecimal purchaseAdditionalDiscount;
  private String sgst;
  private String cgst;
  private Map<String, Object> verticalFields;
  /** Set after lock when the line became an inventory lot. */
  private String inventoryId;
}
