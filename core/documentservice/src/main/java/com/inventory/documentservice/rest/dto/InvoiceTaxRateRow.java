package com.inventory.documentservice.rest.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

/**
 * The GST an invoice charges at one rate: the taxable value of every line at that rate and the
 * CGST and SGST on it -- or, on an interstate supply, the IGST at the combined rate. An invoice with goods at 5% and at 18% prints two of these, because a
 * single SGST and CGST row can only name one rate and was labelled with whichever rate the first
 * line happened to carry.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceTaxRateRow {
  private BigDecimal cgstPercent; // e.g. 9 for 9%
  private BigDecimal sgstPercent; // e.g. 9 for 9%
  private BigDecimal taxableValue;
  private BigDecimal cgstAmount;
  private BigDecimal sgstAmount;
  private BigDecimal igstAmount; // interstate only; CGST and SGST are then zero
  private BigDecimal igstPercent; // the combined rate, e.g. 18 for 9% + 9%
}
