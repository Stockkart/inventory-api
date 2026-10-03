package com.inventory.product.utils;

import com.inventory.product.domain.model.enums.BillingMode;
import com.inventory.product.domain.model.enums.SchemeType;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.PurchaseItem;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Utility methods for checkout calculations: billable quantity, pricing, tax applicability.
 */
public final class CheckoutUtils {

  private CheckoutUtils() {}

  /**
   * Billable quantity as decimal for amount calculations.
   * - PERCENTAGE: full quantity (scheme applied on price).
   * - FIXED_UNITS: quantity * schemePayFor / (schemePayFor + schemeFree), e.g. 19+1 → pay 95% of qty.
   * - No scheme: full quantity.
   */
  public static BigDecimal getBillableQuantityAsDecimal(PurchaseItem item) {
    BigDecimal totalQty = getQuantityAsPricingUnits(item);
    if (totalQty.compareTo(BigDecimal.ZERO) <= 0) {
      return BigDecimal.ZERO;
    }
    if (item.getSchemeType() == SchemeType.PERCENTAGE) {
      return totalQty;
    }
    if (item.getSchemeType() == SchemeType.FIXED_UNITS && item.getSchemePayFor() != null && item.getSchemePayFor() > 0
        && item.getSchemeFree() != null && item.getSchemeFree() >= 0) {
      BigDecimal payFor = BigDecimal.valueOf(item.getSchemePayFor());
      BigDecimal free = BigDecimal.valueOf(item.getSchemeFree());
      BigDecimal sum = payFor.add(free);
      if (sum.compareTo(BigDecimal.ZERO) <= 0) {
        return totalQty;
      }
      return totalQty.multiply(payFor).divide(sum, 4, RoundingMode.HALF_UP);
    }
    return totalQty;
  }

  /** Quantity in pricing units (converts base qty using unitFactor when applicable). */
  public static BigDecimal getQuantityAsPricingUnits(PurchaseItem item) {
    if (item.getBaseQuantity() != null && item.getUnitFactor() != null && item.getUnitFactor() > 0) {
      return BigDecimal.valueOf(item.getBaseQuantity())
          .divide(BigDecimal.valueOf(item.getUnitFactor()), 4, RoundingMode.HALF_UP);
    }
    return item.getQuantity() != null ? item.getQuantity() : BigDecimal.ZERO;
  }

  /**
   * Effective selling price per unit. When schemeType is PERCENTAGE, scheme is applied on price:
   * effectivePrice = priceToRetail * (1 - schemePercentage/100).
   */
  public static BigDecimal getEffectiveSellingPricePerUnit(PurchaseItem item) {
    BigDecimal price = item.getPriceToRetail() != null ? item.getPriceToRetail() : BigDecimal.ZERO;
    if (item.getSchemeType() == SchemeType.PERCENTAGE && item.getSchemePercentage() != null
        && item.getSchemePercentage().signum() > 0) {
      BigDecimal pct = item.getSchemePercentage();
      return price.multiply(BigDecimal.ONE.subtract(pct.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)));
    }
    return price;
  }

  public static BillingMode normalizeBillingMode(BillingMode billingMode) {
    return billingMode != null ? billingMode : BillingMode.REGULAR;
  }

  public static BillingMode resolveInventoryBillingMode(Inventory inventory) {
    return normalizeBillingMode(inventory != null ? inventory.getBillingMode() : null);
  }

  public static boolean isTaxApplicable(BillingMode billingMode) {
    return normalizeBillingMode(billingMode) == BillingMode.REGULAR;
  }

  /** Returns true when item is selling at MRP (priceToRetail equals maximumRetailPrice). MRP is tax-inclusive. */
  public static boolean isSellingAtMrp(PurchaseItem item) {
    if (item == null || item.getPriceToRetail() == null || item.getMaximumRetailPrice() == null) {
      return false;
    }
    return item.getPriceToRetail().compareTo(item.getMaximumRetailPrice()) == 0;
  }

  /**
   * The line's combined GST rate (CGST + SGST, e.g. 18 for 9 + 9), from the rates on the line
   * itself. Zero when the line carries none, which is also what a BASIC bill leaves behind.
   */
  public static BigDecimal combinedGstRate(PurchaseItem item) {
    return parseRate(item.getCgst()).add(parseRate(item.getSgst()));
  }

  /**
   * The selling price per unit with any GST already inside it taken out.
   *
   * <p>A line sold at MRP charges no GST on top, because MRP already includes it. That tax is
   * still collected, so the bill has to state it: the price is split into its taxable value and
   * the GST inside it rather than treated as all taxable value. Any other line is priced before
   * tax and is returned unchanged.
   *
   * <p>MRP 115 at 5% is 109.52 taxable and 5.48 GST, not 115 taxable and no GST.
   */
  public static BigDecimal getTaxablePricePerUnit(PurchaseItem item) {
    BigDecimal price = getEffectiveSellingPricePerUnit(item);
    if (!isSellingAtMrp(item)) {
      return price;
    }
    BigDecimal rate = combinedGstRate(item);
    if (rate.signum() <= 0) {
      return price;
    }
    return price.multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(100).add(rate), 6, RoundingMode.HALF_UP);
  }

  private static BigDecimal parseRate(String rate) {
    if (rate == null || rate.isBlank()) {
      return BigDecimal.ZERO;
    }
    try {
      return new BigDecimal(rate.trim());
    } catch (NumberFormatException e) {
      return BigDecimal.ZERO;
    }
  }

  /** Tax applicable only when billing mode is REGULAR and item is NOT selling at MRP. */
  public static boolean isTaxApplicableForItem(PurchaseItem item, BillingMode billingMode) {
    return isTaxApplicable(billingMode) && !isSellingAtMrp(item);
  }

  /** When PERCENTAGE: set schemePayFor/schemeFree to null. When FIXED_UNITS: set schemePercentage to null. */
  public static void normalizeSchemeFields(PurchaseItem item) {
    if (item.getSchemeType() == SchemeType.PERCENTAGE) {
      item.setSchemePayFor(null);
      item.setSchemeFree(null);
    } else if (item.getSchemeType() == SchemeType.FIXED_UNITS) {
      item.setSchemePercentage(null);
    }
  }

  /** Apply billing mode to item and clear tax fields when BASIC. */
  public static void applyItemTaxMode(PurchaseItem item, BillingMode billingMode) {
    item.setBillingMode(normalizeBillingMode(billingMode));
    if (!isTaxApplicable(billingMode)) {
      item.setCgst(null);
      item.setSgst(null);
    }
  }
}
