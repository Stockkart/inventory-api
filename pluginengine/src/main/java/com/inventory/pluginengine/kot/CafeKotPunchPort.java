package com.inventory.pluginengine.kot;

import java.util.Optional;

/**
 * Reads back one cafe kitchen ticket.
 *
 * <p>Implementations own their own schema. Core orchestration talks only to this interface,
 * because core modules cannot see plugin domain classes — the same arrangement as
 * {@link com.inventory.pluginengine.cart.CartLineContributor} and
 * {@link com.inventory.pluginengine.cart.CheckoutCompletionHandler}.
 *
 * <p>Deliberately narrow: this exists only to let {@code core/product} read back one ticket to
 * render it. It is not a general running-order capability — see the retired {@code
 * RunningOrderStore} for what that looked like and why it is gone.
 *
 * <p>The cart-punch capability this port used to also expose is retired: composing a KOT now
 * happens on its own tab, with no delta to reconcile against a cart.
 */
public interface CafeKotPunchPort {

  String getVerticalId();

  /** Scoped by shop: a request for another shop's ticket resolves to nothing. */
  Optional<CafeKotTicket> findKot(String shopId, String kotId);
}
