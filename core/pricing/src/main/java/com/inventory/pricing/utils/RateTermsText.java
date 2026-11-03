package com.inventory.pricing.utils;

import com.inventory.pricing.domain.model.Rate;
import com.inventory.pricing.domain.model.Scheme;
import com.inventory.pricing.utils.constants.PricingConstants;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** One-line wording of the terms a rate was built from, e.g. "PTR 100.00 − 8% · 10+1". */
public final class RateTermsText {

  private RateTermsText() {}

  /** The terms text for a stored rate; null for a legacy rate that has no base. */
  public static String of(Rate rate) {
    if (rate == null) return null;
    return of(rate.getBase(), rate.getBaseValue(), rate.getDiscountPercent(), rate.getScheme());
  }

  public static String of(String base, BigDecimal baseValue, BigDecimal discountPercent, Scheme scheme) {
    String label = baseLabel(base);
    if (label == null || baseValue == null) return null;
    StringBuilder text = new StringBuilder(label)
        .append(' ').append(baseValue.setScale(2, RoundingMode.HALF_UP).toPlainString());
    if (discountPercent != null && discountPercent.signum() > 0) {
      text.append(" − ").append(discountPercent.stripTrailingZeros().toPlainString()).append('%');
    }
    String schemeText = schemeText(scheme);
    if (schemeText != null) text.append(" · ").append(schemeText);
    return text.toString();
  }

  private static String baseLabel(String base) {
    if (base == null) return null;
    return switch (base) {
      case PricingConstants.RATE_BASE_PRICE_TO_RETAIL -> "PTR";
      case PricingConstants.RATE_BASE_MAXIMUM_RETAIL_PRICE -> "MRP";
      case PricingConstants.RATE_BASE_SELLING_PRICE -> "SP";
      default -> null;
    };
  }

  private static String schemeText(Scheme scheme) {
    if (scheme == null || scheme.getSchemeType() == null) return null;
    if (PricingConstants.SCHEME_TYPE_FIXED_UNITS.equals(scheme.getSchemeType())
        && scheme.getSchemePayFor() != null && scheme.getSchemeFree() != null) {
      return scheme.getSchemePayFor() + "+" + scheme.getSchemeFree();
    }
    if (PricingConstants.SCHEME_TYPE_PERCENTAGE.equals(scheme.getSchemeType())
        && scheme.getSchemePercentage() != null) {
      return scheme.getSchemePercentage().stripTrailingZeros().toPlainString() + "% scheme";
    }
    return null;
  }
}
