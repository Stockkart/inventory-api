package com.inventory.taxation.rest.dto;

import com.inventory.taxation.domain.model.GstRefundLine;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/** CDNUR tab: Summary For CDNUR(9B) - No. of Notes, Total Note Value, Total Taxable Value, Total Cess */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GstCdnurTabDto {
  private GstCdnurSummaryDto summary;
  private List<GstRefundLine> lines;

  public static GstCdnurTabDto fromLines(List<GstRefundLine> lines) {
    if (lines == null || lines.isEmpty()) {
      return new GstCdnurTabDto(new GstCdnurSummaryDto(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), List.of());
    }
    int noOfNotes = lines.size();
    BigDecimal totalNoteValue = lines.stream().map(GstRefundLine::getNoteValue)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalTaxableValue = lines.stream().map(GstRefundLine::getTaxableValue)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalCess = lines.stream().map(GstRefundLine::getCessAmount)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    return new GstCdnurTabDto(new GstCdnurSummaryDto(noOfNotes, totalNoteValue, totalTaxableValue, totalCess), lines);
  }
}
