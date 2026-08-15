package com.inventory.analytics.mis.rest.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One calendar day of completed sales (and refunds posted that day). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MisSalesRowDto {
  private LocalDate date;
  private long orderCount;
  private BigDecimal cash;
  private BigDecimal online;
  private BigDecimal credit;
  private BigDecimal subTotal;
  private BigDecimal tax;
  private BigDecimal discount;
  private BigDecimal grandTotal;
  private BigDecimal cost;
  private BigDecimal profit;
  private BigDecimal margin;
  private long refundCount;
  private BigDecimal refundAmount;
  private BigDecimal netSales;
}
