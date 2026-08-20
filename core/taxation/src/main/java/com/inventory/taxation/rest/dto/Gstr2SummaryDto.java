package com.inventory.taxation.rest.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * The figures a GSTR-2 tab is headed with, matching the portal's own export.
 *
 * <p>Two of them are counted rather than summed, and the difference matters: a
 * tab holds one row per tax rate, so an invoice bearing two rates appears twice.
 * The invoice count and the invoice value are therefore taken over distinct
 * invoices -- an invoice is worth what it is worth however many rates it carries
 * -- while the taxable value and the tax are summed over every row, since each
 * row carries only its own rate's share.
 *
 * <p>A field left null is one the tab does not have; a supplier count means
 * nothing on the HSN summary.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Gstr2SummaryDto {

  private Integer noOfSuppliers;
  private Integer noOfInvoices;
  private BigDecimal totalInvoiceValue;
  private BigDecimal taxableValue;
  private BigDecimal integratedTaxPaid;
  private BigDecimal centralTaxPaid;
  private BigDecimal stateUtTaxPaid;
  private BigDecimal cessAmount;
}
