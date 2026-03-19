package com.inventory.taxation.domain.gstr2;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * ITCR - Input Tax Credit Reversal.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Gstr2ItcrLine {

  private String description;
  private String toBeAddedOrReduced;
  private BigDecimal itcIntegratedTaxAmount;
  private BigDecimal itcCentralTaxAmount;
  private BigDecimal itcStateUtTaxAmount;
  private BigDecimal itcCessAmount;
}
