package com.inventory.pricing.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Purchase scheme/deal from vendor.
 * FIXED_UNITS: pay for schemePayFor, get schemeFree free (e.g. 10+2).
 * PERCENTAGE: schemePercentage extra free (e.g. 10%).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseScheme {

  /** FIXED_UNITS or PERCENTAGE */
  private String schemeType;

  /** When FIXED_UNITS: pay for this many (e.g. 10) */
  private Integer schemePayFor;

  /** When FIXED_UNITS: free units per batch (e.g. 2) */
  private Integer schemeFree;

  /** When PERCENTAGE: extra free % (e.g. 10 = 10%) */
  private BigDecimal schemePercentage;
}
