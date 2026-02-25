package com.inventory.taxation.rest.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Summary for B2B(4) – No. of Recipients, No. of Invoices, Total Invoice Value, Taxable Value, Cess Amount.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GstB2bSummaryDto {

  private int noOfRecipients;
  private int noOfInvoices;
  private BigDecimal totalInvoiceValue;
  private BigDecimal taxableValue;
  private BigDecimal cessAmount;
}
