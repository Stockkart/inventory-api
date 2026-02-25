package com.inventory.taxation.rest.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Summary For HSN(12): No. of HSN, Total Value, Total Taxable Value, Total Integrated Tax, Total Central Tax, Total State/UT Tax, Total Cess */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GstHsnSummaryDto {
  private int noOfHsn;
  private BigDecimal totalValue;
  private BigDecimal totalTaxableValue;
  private BigDecimal totalIntegratedTax;
  private BigDecimal totalCentralTax;
  private BigDecimal totalStateUtTax;
  private BigDecimal totalCess;
}
