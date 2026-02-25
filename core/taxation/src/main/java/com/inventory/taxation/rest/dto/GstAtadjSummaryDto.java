package com.inventory.taxation.rest.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Summary For Advance Adjusted(11B): Total Advance Adjusted, Total Cess */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GstAtadjSummaryDto {
  private BigDecimal totalAdvanceAdjusted;
  private BigDecimal totalCess;
}
