package com.inventory.pluginengine.kot;

import java.util.List;
import java.util.Optional;

/**
 * Punches a cafe cart into kitchen tickets and reads one ticket back. Implementations own their own schema. Core orchestration talks only to this
 * interface, because core modules cannot see plugin domain classes — the same arrangement as
 * {@link com.inventory.pluginengine.cart.CartLineContributor} and
 * {@link com.inventory.pluginengine.cart.CheckoutCompletionHandler}.
 *
 * <p>Deliberately narrow: the punch, the reprint, and reading back one ticket to render it.
 * There is no separate cancellation call: a line the cashier takes back off the cart is a
 * negative delta on the next punch, which is the one path that tells the kitchen to stop. It is not a general running-order capability — see the retired
 * {@code RunningOrderStore} for what that looked like and why it is gone. There is no tab
 * vocabulary here: the Sell cart <i>is</i> the running order, and a punch sends the difference
 * between it and what the kitchen already has.
 *
 * <p><strong>There are no MongoDB transactions in this codebase.</strong> {@link #punch} must be
 * safe to retry; it must not depend on multi-document atomicity.
 */
public interface CafeKotPort {

  String getVerticalId();

  /**
   * Punches the cart, or finishes a punch an earlier attempt left half-done.
   *
   * <p>Idempotent on {@code (shopId, idempotencyKey)}: a replayed punch with the same key returns
   * the original tickets and creates nothing new.
   *
   * @return the tickets this punch stands for; empty when the cart owed the kitchen nothing.
   */
  List<CafeKotTicket> punch(String shopId, String userId, String purchaseId, String idempotencyKey);

  /** Scoped by shop: a request for another shop's ticket resolves to nothing. */
  Optional<CafeKotTicket> findKot(String shopId, String kotId);

  /** Re-issues an already-issued ticket for the frontend to fetch and print again. Creates no new ticket. */
  CafeKotTicket reprint(String shopId, String kotId, String idempotencyKey);
}
