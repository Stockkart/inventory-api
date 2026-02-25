package com.inventory.taxation.rest.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Summary For Advance Received: Total Advance Received, Total Cess */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GstAtSummaryDto {
  private BigDecimal totalAdvanceReceived;
  private BigDecimal totalCess;
}
