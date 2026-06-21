package com.inventory.pluginengine.cart;

/**
 * Vertical hook after a purchase is marked COMPLETED — stock policy, tokens, order extensions.
 */
public interface CheckoutCompletionHandler {

  String getVerticalId();

  default void validateBeforeComplete(CheckoutCompletionContext context) {}

  CheckoutCompletionResult onPurchaseCompleted(CheckoutCompletionContext context);
}
