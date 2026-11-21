package com.inventory.pricing.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * A named price rate (e.g., "Rate-A" = 100, "Rate-B" = 80).
 * Used in Pricing.rates; defaultRate references one by name.
 *
 * <p>The fields after {@code price} are null for a legacy, hand-typed rate, which behaves exactly
 * as a name and price always did. A rule-generated rate records the terms it was built from.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Rate {
  private String name;
  /** The final per-unit figure. */
  private BigDecimal price;
  /** The rule base used (sellingPrice, priceToRetail, maximumRetailPrice); null = legacy rate. */
  private String base;
  /** The base amount used, e.g. the PTR of 100. */
  private BigDecimal baseValue;
  private BigDecimal discountPercent;
  private Scheme scheme;
  /** True when terms were overridden for this batch, or the price was hand-typed. */
  private Boolean manual;
  /** INCLUSIVE or EXCLUSIVE: how a line at this rate will bill. */
  private String gstMode;

  public Rate(String name, BigDecimal price) {
    this.name = name;
    this.price = price;
  }
}
