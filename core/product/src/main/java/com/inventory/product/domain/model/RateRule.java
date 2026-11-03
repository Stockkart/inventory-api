package com.inventory.product.domain.model;

import com.inventory.pricing.api.SchemeTerms;
import com.inventory.product.domain.model.enums.RateBase;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One shop rate rule: a name, a base, and a discount, a scheme, or both. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RateRule {

  /** Unique within the shop, case-insensitive. */
  private String name;

  private RateBase base;

  /** 0..100; null = no discount. */
  private BigDecimal discountPercent;

  private SchemeTerms scheme;
}
