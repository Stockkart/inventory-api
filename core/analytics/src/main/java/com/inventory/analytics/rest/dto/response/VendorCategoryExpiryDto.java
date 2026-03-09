package com.inventory.analytics.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VendorCategoryExpiryDto {
  private String vendorId;
  private String vendorName;
  private String businessType;
  private Integer totalReceived;
  private Integer totalExpired;
  private BigDecimal expiryPercentage;
  private BigDecimal expiredStockValue;
}
