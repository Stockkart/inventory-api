package com.inventory.plan.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A cart priced by the server. Quote and checkout both come from this, so they always agree.
 * {@code addOnOnly} carts have no plan line; {@code plan} is then the shop's current plan, which the
 * add-ons were checked against and which the order does not buy, and {@code termEndsAt} is the end
 * of the paid term it was priced against: annual add-ons end then. Null for plan carts.
 */
public record PricedCart(
    Plan plan,
    List<OrderLine> items,
    BigDecimal subtotal,
    BigDecimal discountTotal,
    BigDecimal walletCredit,
    BigDecimal grandTotal,
    int durationMonths,
    boolean addOnOnly,
    Instant termEndsAt) {

  public PricedCart {
    items = List.copyOf(items);
  }

  /** A plan cart. */
  public PricedCart(Plan plan, List<OrderLine> items, BigDecimal subtotal, BigDecimal discountTotal,
      BigDecimal walletCredit, BigDecimal grandTotal, int durationMonths) {
    this(plan, items, subtotal, discountTotal, walletCredit, grandTotal, durationMonths, false, null);
  }
}
