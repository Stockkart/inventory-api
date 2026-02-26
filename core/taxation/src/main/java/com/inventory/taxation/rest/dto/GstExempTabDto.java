package com.inventory.taxation.rest.dto;

import com.inventory.taxation.domain.model.GstExemptLine;
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
    BigDecimal totalNilRated = lines.stream().map(GstExemptLine::getNilRatedSupplies)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalExempted = lines.stream().map(GstExemptLine::getExemptedOtherThanNilOrNonGst)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalNonGst = lines.stream().map(GstExemptLine::getNonGstSupplies)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    return new GstExempTabDto(new GstExempSummaryDto(totalNilRated, totalExempted, totalNonGst), lines);
  }
}
