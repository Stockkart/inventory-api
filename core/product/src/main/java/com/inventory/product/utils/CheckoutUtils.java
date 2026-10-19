package com.inventory.product.utils;

import com.inventory.common.util.GstMath;
import com.inventory.pricing.utils.SchemePriceUtils;
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
    return SchemePriceUtils.billableQuantity(totalQty, schemeTypeName(item),
        item.getSchemePayFor(), item.getSchemeFree());
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
    return SchemePriceUtils.percentagePrice(price, schemeTypeName(item), item.getSchemePercentage());
  }

  /** The scheme type as pricing names it, which does not see product's enum. */
  private static String schemeTypeName(PurchaseItem item) {
    SchemeType schemeType = item.getSchemeType();
    return schemeType == null ? null : schemeType.name();
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
    if (item == null) {
      return false;
    }
    return SchemePriceUtils.isAtMrp(item.getPriceToRetail(), item.getMaximumRetailPrice());
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

  /**
   * The value a line's GST is worked on, for {@code billableQuantity} of it: the taxable price
   * per unit × the quantity × (1 − the line's additional discount). Left unrounded, so each tax
   * head rounds once, on the whole line.
   *
   * <p>The sale and its return both use this, so a return reverses exactly the tax the sale
   * stated for the same quantity.
   */
  public static BigDecimal getTaxableLineValue(PurchaseItem item, BigDecimal billableQuantity) {
    BigDecimal value = getTaxablePricePerUnit(item).multiply(billableQuantity);
    if (item.getSaleAdditionalDiscount() != null
        && item.getSaleAdditionalDiscount().compareTo(BigDecimal.ZERO) > 0) {
      value = value.multiply(BigDecimal.ONE.subtract(
          item.getSaleAdditionalDiscount().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)));
    }
    return value;
  }

  /**
   * One GST head (CGST or SGST) on a line's taxable value: value × rate / 100, rounded to 2 dp
   * HALF_UP. An interstate line charges the two heads' sum as IGST, never the combined rate in
   * one step, so its IGST is the same paise the two heads would have been.
   */
  public static BigDecimal gstHeadAmount(BigDecimal taxableValue, String rate) {
    BigDecimal fraction = GstMath.parseGstRate(rate).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
    return taxableValue.multiply(fraction).setScale(2, RoundingMode.HALF_UP);
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
