package com.inventory.pluginengine.cart;

/**
 * Recomputes a cart's stored money from its stored lines.
 *
 * <p>A vertical plugin that appends lines to a bill without going through the add-to-cart path —
 * {@code plugins/cafe}'s flush does exactly that, in one raw {@code $push} — leaves the bill's
 * {@code subTotal}, {@code taxTotal}, {@code sgstAmount}, {@code cgstAmount} and {@code
 * grandTotal} untouched. Nothing downstream repairs them: checkout completion reads the
 * <b>stored</b> {@code grandTotal}. So the appending module has to ask for the recompute, and the
 * arithmetic it asks for must be the very arithmetic the add-to-cart path uses, or the total will
 * jump the next time a cashier adds anything in Sell.
 *
 * <p>Hence a port rather than a copy: the one implementation lives in {@code core/product} beside
 * that arithmetic and calls it directly. This interface is deliberately the smallest thing that
 * can express the need — no totals in, no totals out, nothing about lines.
 */
public interface CartTotalsPort {

  /**
   * Recomputes and stores the money on this open cart from the lines it currently holds.
   *
   * <p>A no-op when no such cart exists for this shop; scoped by {@code shopId} like every other
   * read and write in the system.
   */
  void recalculateTotals(String shopId, String purchaseId);
}
