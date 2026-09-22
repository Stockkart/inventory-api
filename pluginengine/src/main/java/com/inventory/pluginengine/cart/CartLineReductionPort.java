package com.inventory.pluginengine.cart;

/**
 * Tells a vertical plugin that an open cart's line was reduced or removed, in case the
 * difference is owed to somewhere {@code core/product} does not know about.
 *
 * <p>{@code plugins/cafe}'s kitchen tickets are exactly this: a bill line that already reached
 * the kitchen (its {@code kotSentQuantity}) owes a cancellation for whatever the Sell screen
 * takes back off it — see {@code CafeKotCancelService}. {@code core/product}'s checkout path
 * reduces and removes cart lines for every vertical and cannot see {@code plugins/cafe} to know
 * that, so — as {@link CartTotalsPort} does for the opposite direction on this same branch — the
 * smallest interface that expresses the need stands between them, implemented by the plugin and
 * called from {@code core/product}.
 *
 * <p>Deliberately one method, and deliberately ignorant of what, if anything, is owed: a line
 * with nothing sent anywhere — every grocery, medical and sports line, and a cafe line the
 * kitchen never saw — is a no-op, decided entirely on the implementing side from fields the
 * caller already read off the line. Nothing about tabs, tickets or kitchens is named here; that
 * is {@link com.inventory.pluginengine.kot.CafeKotPort}'s vocabulary, not this one's.
 */
public interface CartLineReductionPort {

  /**
   * A cart line's quantity went down, or the line was removed, by an edit on the Sell screen
   * rather than by a kitchen-side action.
   *
   * @param fromQty the line's quantity immediately before this edit.
   * @param toQty the line's quantity after; {@code 0} for a removed line.
   * @param idempotencyKey stable for this exact logical edit — derived from the same {@code
   *     purchaseId}, {@code lineRef}, {@code fromQty} and {@code toQty} every time — so a retried
   *     request, or two requests racing from the same starting point, resolve to the same
   *     underlying action instead of each withdrawing its own share.
   */
  void lineReduced(
      String shopId,
      String userId,
      String purchaseId,
      String lineRef,
      int fromQty,
      int toQty,
      String idempotencyKey);
}
