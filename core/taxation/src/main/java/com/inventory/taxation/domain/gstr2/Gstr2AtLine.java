package com.inventory.taxation.domain.gstr2;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * AT - Tax liability on advance paid.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Gstr2AtLine {

  private String placeOfSupply;
  private BigDecimal rate;
  private BigDecimal grossAdvancePaid;
  private BigDecimal cessAmount;
}
