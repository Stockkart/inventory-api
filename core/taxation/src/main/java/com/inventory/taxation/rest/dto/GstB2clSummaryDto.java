package com.inventory.taxation.rest.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Summary For B2CL: No. of Invoices, Total Inv Value, Total Taxable Value, Total Cess */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GstB2clSummaryDto {
  private int noOfInvoices;
  private BigDecimal totalInvoiceValue;
  private BigDecimal totalTaxableValue;
  private BigDecimal totalCess;
}
