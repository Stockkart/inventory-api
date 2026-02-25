package com.inventory.taxation.rest.dto;

import com.inventory.taxation.domain.model.GstInvoiceLine;
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
    int noOfInvoices = lines.size();
    BigDecimal totalInvoiceValue = lines.stream().map(GstInvoiceLine::getInvoiceValue)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalTaxableValue = lines.stream().map(GstInvoiceLine::getTaxableValue)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalCess = lines.stream().map(GstInvoiceLine::getCessAmount)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    return new GstB2clTabDto(new GstB2clSummaryDto(noOfInvoices, totalInvoiceValue, totalTaxableValue, totalCess), lines);
  }
}
