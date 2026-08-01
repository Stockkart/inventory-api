package com.inventory.analytics.rest.dto.response;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VendorMoneyMisVendorSummaryDto {
  private String vendorId;
  private String vendorName;
  private BigDecimal openingBalance;
  private BigDecimal closingBalanceInPeriod;
  private BigDecimal currentBalance;
}
