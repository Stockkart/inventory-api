package com.inventory.analytics.rest.dto.vendor;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VendorStockDto {
  private String vendorId;
  private String vendorName;
  private String vendorCompanyName;
  private Integer totalInventoryReceived;
  private Integer totalQuantitySold;
  private Integer totalUnsoldStock;
  private Integer totalExpiredStock;
  private BigDecimal sellThroughPercentage;
  private BigDecimal revenueGenerated;
  private BigDecimal unsoldStockValue;
  private BigDecimal expiredStockValue;
  private Integer numberOfProducts;
  private Integer numberOfLots;
}

