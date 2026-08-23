package com.inventory.taxation.rest.dto;

import com.inventory.taxation.domain.model.GstRefundLine;
import com.inventory.taxation.summary.GstTotals;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/** CDNR tab: Summary For CDNR(9B) - No. of Recipients, No. of Notes, Total Note Value, Total Taxable Value, Total Cess */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GstCdnrTabDto {
  private GstCdnrSummaryDto summary;
  private List<GstRefundLine> lines;

  public static GstCdnrTabDto fromLines(List<GstRefundLine> lines) {
    if (lines == null || lines.isEmpty()) {
      return new GstCdnrTabDto(new GstCdnrSummaryDto(0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), List.of());
    }
    int noOfRecipients = GstTotals.countDistinct(lines, GstRefundLine::getRecipientGstin);
    int noOfNotes = GstTotals.countDistinct(lines, GstRefundLine::getNoteNumber);
    BigDecimal totalNoteValue = GstTotals.sum(lines, GstRefundLine::getNoteValue);
    BigDecimal totalTaxableValue = GstTotals.sum(lines, GstRefundLine::getTaxableValue);
    BigDecimal totalCess = GstTotals.sum(lines, GstRefundLine::getCessAmount);
    return new GstCdnrTabDto(new GstCdnrSummaryDto(noOfRecipients, noOfNotes, totalNoteValue, totalTaxableValue, totalCess), lines);
  }
}
