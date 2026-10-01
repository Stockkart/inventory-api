package com.inventory.product.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** The GST a bill charges at one rate: the taxable value of its lines at that rate, and the CGST and SGST on it. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GstRateRowDto {
  private BigDecimal cgstPercent; // e.g. 9 for 9%
  private BigDecimal sgstPercent; // e.g. 9 for 9%
  private BigDecimal taxableValue;
  private BigDecimal cgstAmount;
  private BigDecimal sgstAmount;
}
