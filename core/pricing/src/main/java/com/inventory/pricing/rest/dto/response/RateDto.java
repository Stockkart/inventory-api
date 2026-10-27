package com.inventory.pricing.rest.dto.response;

import com.inventory.pricing.api.RateTerms;
import com.inventory.pricing.api.SchemeTerms;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RateDto {
  private String name;
  private BigDecimal price;
  private String base;
  private BigDecimal baseValue;
  private BigDecimal discountPercent;
  private SchemeDto scheme;
  private Boolean manual;
  private String gstMode;
  /** Read-only, built from the terms above, e.g. "PTR 100.00 − 8% · 10+1"; null for legacy rates. */
  private String termsText;

  public RateDto(String name, BigDecimal price) {
    this.name = name;
    this.price = price;
  }

  /** This rate as {@link RateTerms}, for other modules. */
  public RateTerms toTerms() {
    SchemeTerms terms = scheme == null ? null : new SchemeTerms(scheme.getSchemeType(),
        scheme.getSchemePayFor(), scheme.getSchemeFree(), scheme.getSchemePercentage());
    return new RateTerms(name, price, base, baseValue, discountPercent, terms);
  }
}
