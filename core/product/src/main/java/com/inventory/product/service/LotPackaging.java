package com.inventory.product.service;

import com.inventory.product.domain.model.UnitConversion;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pack-factor arithmetic for inventory lots. Display counts are in the pack unit when the product
 * has a conversion (else the base unit); base counts are always display × factor.
 */
public final class LotPackaging {

  private LotPackaging() {}

  /** Base units in one display unit; 1 when the product has no pack conversion. */
  public static int factor(UnitConversion conversion) {
    if (conversion == null || conversion.getFactor() == null || conversion.getFactor() <= 0) {
      return 1;
    }
    return conversion.getFactor();
  }

  public static int toBase(BigDecimal displayCount, int factor) {
    if (displayCount == null) {
      return 0;
    }
    return displayCount
        .multiply(BigDecimal.valueOf(factor))
        .setScale(0, RoundingMode.HALF_UP)
        .intValue();
  }

  /**
   * True when a stored base count no longer matches display × factor. Fractional sales round the
   * display count to 4 decimals, so drift under half a pack is tolerated; a lot linked to a product
   * with a different pack factor is off by whole multiples and always trips this.
   */
  public static boolean isBaseCountDrifted(Integer baseCount, BigDecimal displayCount, int factor) {
    if (baseCount == null || displayCount == null) {
      return false;
    }
    long diff = Math.abs((long) toBase(displayCount, factor) - baseCount);
    return diff * 2 > factor;
  }

  /** Human label such as {@code 1 × 60 PAC} or {@code BTL}. */
  public static String describe(String baseUnit, UnitConversion conversion) {
    int factor = factor(conversion);
    if (factor > 1) {
      String unit = conversion != null && StringUtils.hasText(conversion.getUnit())
          ? conversion.getUnit().trim()
          : "pack";
      return "1 × " + factor + " " + unit;
    }
    return StringUtils.hasText(baseUnit) ? baseUnit.trim() : "no pack conversion";
  }
}
