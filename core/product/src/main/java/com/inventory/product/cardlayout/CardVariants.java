package com.inventory.product.cardlayout;

import com.inventory.pluginengine.cards.CardVariant;
import com.inventory.product.domain.model.enums.BillingMode;

/**
 * The one place {@code BillingMode} meets {@link CardVariant} (design decision: the plugin SPI must
 * not depend on {@code core/product}'s enums).
 */
public final class CardVariants {

  private CardVariants() {}

  /** {@code BASIC → BASIC}; anything else, including {@code null}, is {@code REGULAR}. */
  public static CardVariant of(BillingMode billingMode) {
    return billingMode == BillingMode.BASIC ? CardVariant.BASIC : CardVariant.REGULAR;
  }
}
