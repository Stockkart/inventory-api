package com.inventory.analytics.mis.rest.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MisSalesSummaryDto {
  private long count;
  private BigDecimal gross;
  private BigDecimal tax;
  private BigDecimal discount;
  private BigDecimal cashTotal;
  private BigDecimal onlineTotal;
  private BigDecimal creditTotal;
  private BigDecimal profit;
  private BigDecimal aov;
  private long refundCount;
  private BigDecimal refundAmount;
  private BigDecimal netSales;
}
