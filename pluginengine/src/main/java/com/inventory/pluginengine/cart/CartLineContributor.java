package com.inventory.pluginengine.cart;

import java.util.List;

/**
 * Builds and validates cart lines for a vertical billing workflow. Core {@code CheckoutService}
 * delegates line resolution here — never branches on vertical id.
 */
public interface CartLineContributor {

  String getVerticalId();

  void validateRequestItems(List<CartLineInput> items);

  List<CartLineSnapshot> buildLines(List<CartLineInput> items, CartBuildContext context);

  /** Stable merge key for cart update (inventory id or menu item id). */
  String lineKey(CartLineSnapshot line);
}
