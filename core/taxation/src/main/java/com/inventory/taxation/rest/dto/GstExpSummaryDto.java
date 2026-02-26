package com.inventory.taxation.rest.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Summary For EXP(6): No. of Invoices, Total Invoice Value, No. of Shipping Bill, Total Taxable Value */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GstExpSummaryDto {
  private int noOfInvoices;
  private BigDecimal totalInvoiceValue;
  private int noOfShippingBills;
  private BigDecimal totalTaxableValue;
}
