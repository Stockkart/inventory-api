package com.inventory.plan.domain.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * A cart priced by the server. Quote and checkout both come from this, so they always agree.
 */
public record PricedCart(
    Plan plan,
    List<OrderLine> items,
    BigDecimal subtotal,
    BigDecimal discountTotal,
    BigDecimal walletCredit,
    BigDecimal grandTotal,
    int durationMonths) {

  public PricedCart {
    items = List.copyOf(items);
  }
}
