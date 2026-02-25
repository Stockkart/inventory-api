package com.inventory.taxation.rest.dto;

import com.inventory.taxation.domain.model.GstInvoiceLine;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/** EXP tab: Summary For EXP(6) - No. of Invoices, Total Invoice Value, No. of Shipping Bills, Total Taxable Value */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GstExpTabDto {
  private GstExpSummaryDto summary;
  private List<GstInvoiceLine> lines;

  public static GstExpTabDto fromLines(List<GstInvoiceLine> lines) {
    if (lines == null || lines.isEmpty()) {
      return new GstExpTabDto(new GstExpSummaryDto(0, BigDecimal.ZERO, 0, BigDecimal.ZERO), List.of());
    }
    int noOfInvoices = lines.size();
    BigDecimal totalInvoiceValue = lines.stream().map(GstInvoiceLine::getInvoiceValue)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    long noOfShippingBills = lines.stream().map(GstInvoiceLine::getShippingBillNo)
        .filter(s -> s != null && !s.isBlank()).distinct().count();
    BigDecimal totalTaxableValue = lines.stream().map(GstInvoiceLine::getTaxableValue)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    return new GstExpTabDto(new GstExpSummaryDto(noOfInvoices, totalInvoiceValue, (int) noOfShippingBills, totalTaxableValue), lines);
  }
}
