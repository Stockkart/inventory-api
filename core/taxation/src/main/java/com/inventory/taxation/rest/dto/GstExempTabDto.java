package com.inventory.taxation.rest.dto;

import com.inventory.taxation.domain.model.GstExemptLine;
import com.inventory.taxation.utils.helper.GstTotals;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/** EXEMP tab: Summary For Nil rated, exempted and non GST outward supplies (8) - Total Nil Rated, Total Exempted, Total Non-GST */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GstExempTabDto {
  private GstExempSummaryDto summary;
  private List<GstExemptLine> lines;

  public static GstExempTabDto fromLines(List<GstExemptLine> lines) {
    if (lines == null || lines.isEmpty()) {
      return new GstExempTabDto(new GstExempSummaryDto(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), List.of());
    }
    BigDecimal totalNilRated = GstTotals.sum(lines, GstExemptLine::getNilRatedSupplies);
    BigDecimal totalExempted = GstTotals.sum(lines, GstExemptLine::getExemptedOtherThanNilOrNonGst);
    BigDecimal totalNonGst = GstTotals.sum(lines, GstExemptLine::getNonGstSupplies);
    return new GstExempTabDto(new GstExempSummaryDto(totalNilRated, totalExempted, totalNonGst), lines);
  }
}
