package com.inventory.taxation.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * HSN-wise summary line for GSTR-1 (B2B and B2C).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GstHsnLine {

  private String hsn;
  private String description;
  private String uqc;            // Unit quantity code
  private BigDecimal totalQuantity;
  private BigDecimal totalValue;
  private BigDecimal rate;
  private BigDecimal taxableValue;
  private BigDecimal integratedTaxAmount;
  private BigDecimal centralTaxAmount;
  private BigDecimal stateUtTaxAmount;
  private BigDecimal cessAmount;
  private boolean b2b;            // true = B2B sheet, false = B2C sheet
}
