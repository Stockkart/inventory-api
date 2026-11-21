package com.inventory.product.utils;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.enums.SchemeType;
import com.inventory.product.rest.dto.request.UpdateInventoryRequest;

/**
 * Rules for a lot update that sends a deal type without the deal itself, as the product search
 * Edit dialog does when only its deal-type dropdown changes. Applied against the stored lot before
 * the update is validated and saved.
 */
public final class SchemeUpdateUtils {

  public static final String PURCHASE_TYPE_NEEDS_SCHEME =
      "To change the purchase deal type, enter the purchase scheme as well (e.g. 10% or 10+2)";
  public static final String SALE_TYPE_NEEDS_SCHEME =
      "To change the sale deal type, enter the sale scheme as well (e.g. 15% or 10+2)";

  private SchemeUpdateUtils() {}

  /**
   * Drops a type-only change that matches the stored deal type, and refuses one that differs.
   * Sent on its own, a type would otherwise replace the stored deal with an empty one (a purchase
   * 19+1 became "percentage, no value", changing the effective cost).
   *
   * <p>A sale {@code FIXED_UNITS} with every part null is left alone: it is also how the dialog
   * clears the sale scheme, and the two requests cannot be told apart.
   */
  public static void dropOrRefuseTypeOnlyChanges(UpdateInventoryRequest request, Inventory stored) {
    if (request == null || stored == null) {
      return;
    }
    if (isPurchaseTypeOnly(request)) {
      if (request.getPurchaseSchemeType() != storedType(stored.getPurchaseSchemeType())) {
        throw new ValidationException(PURCHASE_TYPE_NEEDS_SCHEME);
      }
      request.setPurchaseSchemeType(null);
    }
    if (isSaleTypeOnly(request) && request.getSchemeType() == SchemeType.PERCENTAGE) {
      if (storedType(stored.getSchemeType()) != SchemeType.PERCENTAGE) {
        throw new ValidationException(SALE_TYPE_NEEDS_SCHEME);
      }
      request.setSchemeType(null);
    }
  }

  private static boolean isPurchaseTypeOnly(UpdateInventoryRequest r) {
    return r.getPurchaseSchemeType() != null
        && r.getPurchaseSchemePayFor() == null
        && r.getPurchaseSchemeFree() == null
        && r.getPurchaseSchemePercentage() == null;
  }

  private static boolean isSaleTypeOnly(UpdateInventoryRequest r) {
    return r.getSchemeType() != null
        && r.getSchemePayFor() == null
        && r.getSchemeFree() == null
        && r.getSchemePercentage() == null
        && r.getScheme() == null;
  }

  /** A lot without a stored deal type shows, and is treated as, free units. */
  private static SchemeType storedType(SchemeType type) {
    return type != null ? type : SchemeType.FIXED_UNITS;
  }
}
