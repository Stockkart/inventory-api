package com.inventory.taxation.rest.dto;

import com.inventory.taxation.domain.model.GstHsnLine;
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
    int noOfHsn = lines.size();
    BigDecimal totalValue = lines.stream().map(GstHsnLine::getTotalValue)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalTaxableValue = lines.stream().map(GstHsnLine::getTaxableValue)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalIntegratedTax = lines.stream().map(GstHsnLine::getIntegratedTaxAmount)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalCentralTax = lines.stream().map(GstHsnLine::getCentralTaxAmount)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalStateUtTax = lines.stream().map(GstHsnLine::getStateUtTaxAmount)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalCess = lines.stream().map(GstHsnLine::getCessAmount)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    return new GstHsnTabDto(new GstHsnSummaryDto(noOfHsn, totalValue, totalTaxableValue, totalIntegratedTax, totalCentralTax, totalStateUtTax, totalCess), lines);
  }
}
