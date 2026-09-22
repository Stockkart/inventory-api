package com.inventory.pluginengine.kot;

import java.util.List;
import java.util.Optional;

/**
 * Punches a cafe cart into kitchen tickets, cancels what a cashier takes back off it, and reads
 * one ticket back. Implementations own their own schema. Core orchestration talks only to this
 * interface, because core modules cannot see plugin domain classes — the same arrangement as
 * {@link com.inventory.pluginengine.cart.CartLineContributor} and
 * {@link com.inventory.pluginengine.cart.CheckoutCompletionHandler}.
 *
 * <p>Deliberately narrow: the punch, cancellation of an already-sent line, reprint, and reading
 * back one ticket to render it. It is not a general running-order capability — see the retired
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

  /**
   * Tells the kitchen to stop making part of an already-sent bill line.
   *
   * @param fromQty the line's quantity before this reduction, as the caller understood it.
   * @param toQty the line's new quantity; 0 for a removed line.
   * @return the tickets this idempotency key stands for; empty when nothing was owed.
   */
  List<CafeKotTicket> cancel(
      String shopId,
      String userId,
      String purchaseId,
      String lineRef,
      int fromQty,
      int toQty,
      String idempotencyKey);

  /** Re-issues an already-issued ticket for the frontend to fetch and print again. Creates no new ticket. */
  CafeKotTicket reprint(String shopId, String kotId, String idempotencyKey);
}
