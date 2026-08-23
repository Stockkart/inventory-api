package com.inventory.taxation.rest.dto;

import com.inventory.taxation.domain.model.GstRefundLine;
import com.inventory.taxation.summary.GstTotals;
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
    int noOfNotes = GstTotals.countDistinct(lines, GstRefundLine::getNoteNumber);
    BigDecimal totalNoteValue = GstTotals.sum(lines, GstRefundLine::getNoteValue);
    BigDecimal totalTaxableValue = GstTotals.sum(lines, GstRefundLine::getTaxableValue);
    BigDecimal totalCess = GstTotals.sum(lines, GstRefundLine::getCessAmount);
    return new GstCdnurTabDto(new GstCdnurSummaryDto(noOfNotes, totalNoteValue, totalTaxableValue, totalCess), lines);
  }
}
