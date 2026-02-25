package com.inventory.taxation.rest.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Summary For B2CS(7): Total Taxable Value, Total Cess */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GstB2csSummaryDto {
  private BigDecimal totalTaxableValue;
  private BigDecimal totalCess;
}
