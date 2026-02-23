package com.inventory.pricing.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * A named price rate (e.g., "Rate-A" = 100, "Rate-B" = 80).
 * Used in Pricing.rates; defaultRate references one by name.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Rate {
  private String name;
  private BigDecimal price;
}
