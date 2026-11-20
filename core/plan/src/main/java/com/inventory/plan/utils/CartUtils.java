package com.inventory.plan.utils;

import com.inventory.plan.rest.dto.request.CreatePlanCheckoutRequest;
import com.inventory.plan.rest.dto.request.QuoteRequest;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/** What kind of cart a quote or checkout request is. */
public final class CartUtils {

  private CartUtils() {}

  /** True when the request names a plan, by code or (checkout only) by legacy plan id. */
  public static boolean hasPlan(QuoteRequest request) {
    if (request == null) {
      return false;
    }
    String planId = request instanceof CreatePlanCheckoutRequest checkout ? checkout.getPlanId() : null;
    return StringUtils.hasText(request.getPlanCode()) || StringUtils.hasText(planId);
  }

  /**
   * An add-on top-up: no plan, at least one add-on. Priced against the shop's current paid plan,
   * which it neither buys nor extends.
   */
  public static boolean isAddOnOnly(QuoteRequest request) {
    return request != null && !hasPlan(request) && !CollectionUtils.isEmpty(request.getAddOns());
  }
}
