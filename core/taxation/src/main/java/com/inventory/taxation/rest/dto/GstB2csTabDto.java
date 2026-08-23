package com.inventory.taxation.rest.dto;

import com.inventory.taxation.domain.model.GstInvoiceLine;
import com.inventory.taxation.summary.GstTotals;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/** B2CS tab: Summary For B2CS(7) - Total Taxable Value, Total Cess */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GstB2csTabDto {
  private GstB2csSummaryDto summary;
  private List<GstB2csLineDto> lines;

  public static GstB2csTabDto fromLines(List<GstInvoiceLine> lines) {
    if (lines == null || lines.isEmpty()) {
      return new GstB2csTabDto(new GstB2csSummaryDto(BigDecimal.ZERO, BigDecimal.ZERO), List.of());
    }
    BigDecimal totalTaxableValue = GstTotals.sum(lines, GstInvoiceLine::getTaxableValue);
    BigDecimal totalCess = GstTotals.sum(lines, GstInvoiceLine::getCessAmount);
    return new GstB2csTabDto(new GstB2csSummaryDto(totalTaxableValue, totalCess), GstB2csLineDto.fromList(lines));
  }
}
