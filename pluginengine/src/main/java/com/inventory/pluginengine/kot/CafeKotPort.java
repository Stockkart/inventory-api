package com.inventory.pluginengine.kot;

import java.util.List;
import java.util.Optional;

/**
 * Composes and sends cafe kitchen tickets. Implementations own their own schema. Core
 * orchestration talks only to this interface, because core modules cannot see plugin domain
 * classes — the same arrangement as {@link com.inventory.pluginengine.cart.CartLineContributor}
 * and {@link com.inventory.pluginengine.cart.CheckoutCompletionHandler}.
 *
 * <p>Deliberately narrow: tab composition, one flush to the kitchen and a bill, cancellation of
 * an already-sent line, reprint, and reading back one ticket to render it. It is not a general
 * running-order capability — see the retired {@code RunningOrderStore} for what that looked like
 * and why it is gone. Renamed from {@code CafeKotPunchPort}: nothing punches any more — a tab is
 * composed separately and flushed, with no delta to reconcile against a cart.
 */
public interface CafeKotPort {

  String getVerticalId();

  /** Scoped by shop: a request for another shop's ticket resolves to nothing. */
  Optional<CafeKotTicket> findKot(String shopId, String kotId);

  /** This cashier's open tabs only — never another cashier's, even in the same shop. */
  List<CafeKotTab> listTabs(String shopId, String userId);

  /** Opens a new, empty tab for this cashier. */
  CafeKotTab openTab(String shopId, String userId);

  /**
   * Adds a line to an open tab, or updates one already on it.
   *
   * @param lineRef null to add a new line for {@code sellableRef}; an existing line's ref to
   *     update its quantity and/or note instead — the frozen department never changes.
   */
  CafeKotTab upsertTabLine(
      String shopId,
      String userId,
      String tabId,
      String lineRef,
      String sellableRef,
      int quantity,
      String note);

  CafeKotTab removeTabLine(String shopId, String userId, String tabId, String lineRef);

  /** The only way a tab leaves the open state. */
  void closeTab(String shopId, String userId, String tabId);

  /**
   * Sends a tab's unsent lines to the kitchen and onto a bill, or finishes a flush an earlier
   * attempt left half-done.
   *
   * @param targetPurchaseId the open bill to append to; null asks for a new one.
   * @return the tickets this idempotency key stands for.
   */
  List<CafeKotTicket> flush(
      String shopId, String userId, String tabId, String targetPurchaseId, String idempotencyKey);

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

  /** Sends an already-issued ticket to the printer again. Creates no new ticket. */
  CafeKotTicket reprint(String shopId, String kotId, String idempotencyKey);
}
