package com.inventory.taxation.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Advance received or adjusted line for GSTR-1 (at, atadj).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GstAdvanceLine {

  private String placeOfSupply;
  private String applicableTaxPct;
  private BigDecimal rate;
  private BigDecimal grossAdvanceReceivedOrAdjusted;
  private BigDecimal cessAmount;
  private boolean adjusted; // true = atadj, false = at
}
