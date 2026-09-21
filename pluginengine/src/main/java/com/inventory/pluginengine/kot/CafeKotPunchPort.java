package com.inventory.pluginengine.kot;

import java.util.List;
import java.util.Optional;

/**
 * Punches a cafe cart into kitchen tickets, and reads one back.
 *
 * <p>Implementations own their own schema. Core orchestration talks only to this interface,
 * because core modules cannot see plugin domain classes — the same arrangement as
 * {@link com.inventory.pluginengine.cart.CartLineContributor} and
 * {@link com.inventory.pluginengine.cart.CheckoutCompletionHandler}.
 *
 * <p>Deliberately narrow: this exists only to let {@code core/product} reach the punch and read
 * back one ticket to render it. It is not a general running-order capability — see the retired
 * {@code RunningOrderStore} for what that looked like and why it is gone.
 *
 * <p><strong>There are no MongoDB transactions in this codebase.</strong> {@link #punch} must be
 * safe to retry; it must not depend on multi-document atomicity.
 */
public interface CafeKotPunchPort {

  String getVerticalId();

  /**
   * Punches the cart, or finishes a punch an earlier attempt left half-done.
   *
   * <p>Idempotent on {@code (shopId, idempotencyKey)}: a replayed punch with the same key returns
   * the original tickets and creates nothing new.
   *
   * @return the tickets this punch stands for
   */
  List<CafeKotTicket> punch(String shopId, String userId, String purchaseId, String idempotencyKey);

  /** Scoped by shop: a request for another shop's ticket resolves to nothing. */
  Optional<CafeKotTicket> findKot(String shopId, String kotId);
}
