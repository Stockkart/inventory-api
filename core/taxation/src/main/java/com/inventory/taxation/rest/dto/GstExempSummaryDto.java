package com.inventory.taxation.rest.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Summary For Nil rated, exempted and non GST outward supplies (8): Total Nil Rated, Total Exempted, Total Non-GST */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GstExempSummaryDto {
  private BigDecimal totalNilRatedSupplies;
  private BigDecimal totalExemptedSupplies;
  private BigDecimal totalNonGstSupplies;
}
