package com.inventory.taxation.rest.dto;

import com.inventory.taxation.domain.model.GstInvoiceLine;
import com.inventory.taxation.utils.helper.GstTotals;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/** B2CL tab: Summary For B2CL(5) - No. of Invoices, Total Inv Value, Total Taxable Value, Total Cess */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GstB2clTabDto {
  private GstB2clSummaryDto summary;
  private List<GstInvoiceLine> lines;

  public static GstB2clTabDto fromLines(List<GstInvoiceLine> lines) {
    if (lines == null || lines.isEmpty()) {
      return new GstB2clTabDto(new GstB2clSummaryDto(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), List.of());
    }
    int noOfInvoices = GstTotals.countDistinct(lines, GstInvoiceLine::getInvoiceNo);
    BigDecimal totalInvoiceValue = GstTotals.sum(lines, GstInvoiceLine::getInvoiceValue);
    BigDecimal totalTaxableValue = GstTotals.sum(lines, GstInvoiceLine::getTaxableValue);
    BigDecimal totalCess = GstTotals.sum(lines, GstInvoiceLine::getCessAmount);
    return new GstB2clTabDto(new GstB2clSummaryDto(noOfInvoices, totalInvoiceValue, totalTaxableValue, totalCess), lines);
  }
}
