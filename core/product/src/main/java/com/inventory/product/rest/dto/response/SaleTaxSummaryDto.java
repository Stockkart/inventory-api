package com.inventory.product.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * A bill's totals as the printed invoice states them, worked from its lines by
 * {@link com.inventory.product.utils.SaleTaxBreakdown}. Screens show these figures as they are.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SaleTaxSummaryDto {
  /** Before the additional discount, at each line's taxable rate. */
  private BigDecimal subTotal;
  private BigDecimal additionalDiscount;
  private BigDecimal taxableValue;
  /** One row per GST rate on the bill, in the order the rates first appear. */
  private List<GstRateRowDto> rates;
  private BigDecimal cgstTotal;
  private BigDecimal sgstTotal;
  private BigDecimal roundOff;
  /** Each line's rate before tax, in line order: what the invoice prints in its RATE column. */
  private List<BigDecimal> lineRates;
}
