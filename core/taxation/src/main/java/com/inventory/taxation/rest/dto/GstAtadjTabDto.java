package com.inventory.taxation.rest.dto;

import com.inventory.taxation.domain.model.GstAdvanceLine;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/** ATADJ tab: Summary For Advance Adjusted(11B) - Total Advance Adjusted, Total Cess */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GstAtadjTabDto {
  private GstAtadjSummaryDto summary;
  private List<GstAdvanceLine> lines;

  public static GstAtadjTabDto fromLines(List<GstAdvanceLine> lines) {
    if (lines == null || lines.isEmpty()) {
      return new GstAtadjTabDto(new GstAtadjSummaryDto(BigDecimal.ZERO, BigDecimal.ZERO), List.of());
    }
    BigDecimal totalAdjusted = lines.stream().map(GstAdvanceLine::getGrossAdvanceReceivedOrAdjusted)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalCess = lines.stream().map(GstAdvanceLine::getCessAmount)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    return new GstAtadjTabDto(new GstAtadjSummaryDto(totalAdjusted, totalCess), lines);
  }
}
