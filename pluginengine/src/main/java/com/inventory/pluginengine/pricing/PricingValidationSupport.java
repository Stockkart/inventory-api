package com.inventory.pluginengine.pricing;

import com.inventory.common.exception.ValidationException;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.util.StringUtils;

public final class PricingValidationSupport {

  private PricingValidationSupport() {}

  public static void validatePriceValues(
      BigDecimal mrp, BigDecimal cp, BigDecimal sp, BigDecimal additionalDiscount) {
    if (mrp != null && mrp.compareTo(BigDecimal.ZERO) < 0) {
      throw new ValidationException("Maximum retail price cannot be negative");
    }
    if (cp != null && cp.compareTo(BigDecimal.ZERO) < 0) {
      throw new ValidationException("Cost price cannot be negative");
    }
    if (sp != null && sp.compareTo(BigDecimal.ZERO) < 0) {
      throw new ValidationException("Selling price cannot be negative");
    }
    if (additionalDiscount != null
        && (additionalDiscount.compareTo(BigDecimal.ZERO) < 0
            || additionalDiscount.compareTo(BigDecimal.valueOf(100)) > 0)) {
      throw new ValidationException("Additional discount must be between 0 and 100");
    }
  }

  public static void validateRates(List<PricingRateEntry> rates, String defaultRate) {
    if (rates == null || rates.isEmpty()) {
      return;
    }
    for (PricingRateEntry rate : rates) {
      if (!StringUtils.hasText(rate.name())) {
        throw new ValidationException("Rate name cannot be blank");
      }
      if (rate.price() == null) {
        throw new ValidationException("Rate price is required: " + rate.name());
      }
      if (rate.price().compareTo(BigDecimal.ZERO) < 0) {
        throw new ValidationException("Rate price cannot be negative: " + rate.name());
      }
    }
    if (StringUtils.hasText(defaultRate)
        && !"priceToRetail".equalsIgnoreCase(defaultRate)
        && !"maximumRetailPrice".equalsIgnoreCase(defaultRate)
        && !"costPrice".equalsIgnoreCase(defaultRate)) {
      if (rates.isEmpty()) {
        throw new ValidationException(
            "defaultRate '"
                + defaultRate
                + "' requires rates, or use 'priceToRetail', 'maximumRetailPrice', or 'costPrice'");
      }
      boolean found = rates.stream().anyMatch(r -> defaultRate.equals(r.name()));
      if (!found) {
        throw new ValidationException(
            "defaultRate '"
                + defaultRate
                + "' must match a rate name in rates, or use 'priceToRetail', 'maximumRetailPrice', or 'costPrice'");
      }
    }
  }

  public static void validateGstRates(String sgst, String cgst) {
    if (StringUtils.hasText(sgst)) {
      try {
        BigDecimal rate = new BigDecimal(sgst.trim());
        if (rate.compareTo(BigDecimal.ZERO) < 0 || rate.compareTo(BigDecimal.valueOf(100)) > 0) {
          throw new ValidationException("SGST rate must be between 0 and 100");
        }
      } catch (NumberFormatException e) {
        throw new ValidationException("Invalid SGST rate format");
      }
    }
    if (StringUtils.hasText(cgst)) {
      try {
        BigDecimal rate = new BigDecimal(cgst.trim());
        if (rate.compareTo(BigDecimal.ZERO) < 0 || rate.compareTo(BigDecimal.valueOf(100)) > 0) {
          throw new ValidationException("CGST rate must be between 0 and 100");
        }
      } catch (NumberFormatException e) {
        throw new ValidationException("Invalid CGST rate format");
      }
    }
  }

  public static BigDecimal resolveEffectivePrice(
      BigDecimal mrp,
      BigDecimal costPrice,
      BigDecimal priceToRetail,
      BigDecimal sellingPrice,
      List<PricingRateEntry> rates,
      String defaultRate) {
    if (sellingPrice != null) {
      return sellingPrice;
    }
    if (!StringUtils.hasText(defaultRate)) {
      return priceToRetail;
    }
    String dr = defaultRate.trim();
    if ("maximumRetailPrice".equalsIgnoreCase(dr)) {
      return mrp != null ? mrp : priceToRetail;
    }
    if ("costPrice".equalsIgnoreCase(dr)) {
      return costPrice != null ? costPrice : priceToRetail;
    }
    if ("priceToRetail".equalsIgnoreCase(dr)) {
      return priceToRetail;
    }
    if (rates != null && !rates.isEmpty()) {
      return rates.stream()
          .filter(r -> dr.equals(r.name()))
          .map(PricingRateEntry::price)
          .findFirst()
          .orElse(priceToRetail);
    }
    return priceToRetail;
  }
}
