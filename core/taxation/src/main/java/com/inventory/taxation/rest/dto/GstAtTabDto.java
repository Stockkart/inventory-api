package com.inventory.taxation.rest.dto;

import com.inventory.taxation.domain.model.GstAdvanceLine;
import com.inventory.taxation.summary.GstTotals;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/** AT tab: Summary For Advance Received - Total Advance Received, Total Cess */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GstAtTabDto {
  private GstAtSummaryDto summary;
  private List<GstAdvanceLine> lines;

  public static GstAtTabDto fromLines(List<GstAdvanceLine> lines) {
    if (lines == null || lines.isEmpty()) {
      return new GstAtTabDto(new GstAtSummaryDto(BigDecimal.ZERO, BigDecimal.ZERO), List.of());
    }
    BigDecimal totalAdvance = GstTotals.sum(lines, GstAdvanceLine::getGrossAdvanceReceivedOrAdjusted);
    BigDecimal totalCess = GstTotals.sum(lines, GstAdvanceLine::getCessAmount);
    return new GstAtTabDto(new GstAtSummaryDto(totalAdvance, totalCess), lines);
  }
}
