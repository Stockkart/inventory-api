package com.inventory.taxation.domain.gstr2;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * ATADJ - Adjustment of advance paid.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Gstr2AtadjLine {

  private String placeOfSupply;
  private BigDecimal rate;
  private BigDecimal grossAdvanceToBeAdjusted;
  private BigDecimal cessAdjusted;
}
