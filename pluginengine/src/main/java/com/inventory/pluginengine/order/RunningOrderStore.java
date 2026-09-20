package com.inventory.pluginengine.order;

import java.util.List;
import java.util.Optional;

/**
 * Persistence for a vertical's running orders and kitchen tickets.
 *
 * <p>Implementations own their own schema. Core orchestration talks only to this interface, because
 * core modules cannot see plugin domain classes — the same arrangement as
 * {@link com.inventory.pluginengine.cart.CartLineContributor} and
 * {@link com.inventory.pluginengine.cart.CheckoutCompletionHandler}.
 *
 * <p><strong>There are no MongoDB transactions in this codebase.</strong> Every method here must be
 * safe to retry; none may depend on multi-document atomicity.
 *
 * <p>Every method takes {@code shopId} first and implementations must scope every lookup by it.
 * Ids travel through REST, so isolation is structural rather than checked case by case.
 */
public interface RunningOrderStore {

  String getVerticalId();

  RunningOrderView openOrder(
      String shopId, String userId, String orderType, String tableLabel, String tokenNo);

  Optional<RunningOrderView> findOrder(String shopId, String orderId);

  List<RunningOrderView> listOpenOrders(String shopId);

  /**
   * Punch one round, splitting the lines into one ticket per department.
   *
   * <p>Idempotent on {@code (shopId, idempotencyKey)}: a replayed punch returns the original
   * tickets and creates nothing. The claim is recorded before any ticket exists, so a retry can
   * also tell a finished punch from one that died mid-flight.
   */
  List<KotView> punch(PunchCommand command);

  Optional<KotView> findKot(String shopId, String kotId);

  /**
   * Void named lines on a ticket. Voiding every line of a ticket marks the ticket itself VOIDED;
   * voiding a subset leaves it ISSUED.
   */
  VoidResult voidLines(
      String shopId, String userId, String kotId, List<String> lineIds, String reason);

  /** Re-fetch a past void operation, so its slip can be reprinted without reprinting the ticket. */
  Optional<VoidResult> findVoidBatch(String shopId, String kotId, String voidBatchId);

  KotView markReprinted(String shopId, String kotId);

  /** Records the settlement cart id. Called before the purchase is completed, never after. */
  RunningOrderView bindPurchase(String shopId, String orderId, String purchaseId);

  RunningOrderView markBilled(String shopId, String userId, String orderId);

  /** Voids every active line and non-voided ticket, then marks the order cancelled. */
  RunningOrderView cancelOrder(String shopId, String userId, String orderId, String reason);
}
