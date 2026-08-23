package com.inventory.taxation.rest.dto;

import com.inventory.taxation.domain.model.GstHsnLine;
import com.inventory.taxation.utils.helper.GstTotals;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/** HSN tab: Summary For HSN(12) - No. of HSN, Total Value, Total Taxable Value, Total Integrated Tax, Total Central Tax, Total State/UT Tax, Total Cess */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GstHsnTabDto {
  private GstHsnSummaryDto summary;
  private List<GstHsnLine> lines;

  public static GstHsnTabDto fromLines(List<GstHsnLine> lines) {
    if (lines == null || lines.isEmpty()) {
      return new GstHsnTabDto(new GstHsnSummaryDto(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), List.of());
    }
    int noOfHsn = GstTotals.countDistinct(lines, GstHsnLine::getHsn);
    BigDecimal totalValue = GstTotals.sum(lines, GstHsnLine::getTotalValue);
    BigDecimal totalTaxableValue = GstTotals.sum(lines, GstHsnLine::getTaxableValue);
    BigDecimal totalIntegratedTax = GstTotals.sum(lines, GstHsnLine::getIntegratedTaxAmount);
    BigDecimal totalCentralTax = GstTotals.sum(lines, GstHsnLine::getCentralTaxAmount);
    BigDecimal totalStateUtTax = GstTotals.sum(lines, GstHsnLine::getStateUtTaxAmount);
    BigDecimal totalCess = GstTotals.sum(lines, GstHsnLine::getCessAmount);
    return new GstHsnTabDto(new GstHsnSummaryDto(noOfHsn, totalValue, totalTaxableValue, totalIntegratedTax, totalCentralTax, totalStateUtTax, totalCess), lines);
  }
}
