package com.inventory.taxation.rest.dto;

import com.inventory.taxation.domain.model.GstRefundLine;
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
    int noOfRecipients = (int) lines.stream().map(GstRefundLine::getRecipientGstin)
        .filter(g -> g != null && !g.isBlank()).distinct().count();
    int noOfNotes = lines.size();
    BigDecimal totalNoteValue = lines.stream().map(GstRefundLine::getNoteValue)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalTaxableValue = lines.stream().map(GstRefundLine::getTaxableValue)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalCess = lines.stream().map(GstRefundLine::getCessAmount)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    return new GstCdnrTabDto(new GstCdnrSummaryDto(noOfRecipients, noOfNotes, totalNoteValue, totalTaxableValue, totalCess), lines);
  }
}
