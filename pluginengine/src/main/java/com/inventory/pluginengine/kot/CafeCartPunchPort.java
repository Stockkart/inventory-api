package com.inventory.pluginengine.kot;

import java.util.List;
import java.util.Optional;

/**
 * The cart side of a cafe KOT punch: what {@code plugins/cafe} needs from the Sell cart, which
 * {@code core/product} owns.
 *
 * <p>The cart is a document in the {@code purchases} collection, and only {@code core/product} may
 * read or write that collection (see {@code module-boundaries.yml}). So the plugin never touches
 * the cart itself: it asks through this port, and {@code core/product} implements it.
 *
 * <p>The atomicity argument stays with the owner. {@link #claimAndReconcile} is still the single
 * write that appends the punch and advances the lines together; see the product implementation.
 */
public interface CafeCartPunchPort {

  /**
   * Appends a punch to the cart and advances its lines, in one write.
   *
   * @return true when the write landed; false when nothing matched, which means either no such
   *     cart in this shop or this idempotency key has already been punched.
   */
  boolean claimAndReconcile(
      String shopId, String purchaseId, String punchId, String idempotencyKey, String userId);

  /** The cart's punch state. Scoped by shop: another shop's cart resolves to nothing. */
  Optional<Cart> findCart(String shopId, String purchaseId);

  /** Marks one punch COMPLETE and records the tickets it stands for. Scoped by shop. */
  void markPunchComplete(String shopId, String purchaseId, String punchId, List<String> kotIds);

  /**
   * What a punch reads off the cart.
   *
   * @param id the purchase id
   * @param tableLabel dine-in table, or null
   * @param tokenNo daily order token, or null
   * @param punches every punch on the cart, in the order they were pressed; never null
   */
  record Cart(String id, String tableLabel, String tokenNo, List<Punch> punches) {

    public Cart {
      punches = punches == null ? List.of() : List.copyOf(punches);
    }
  }

  /**
   * One press of Print KOT.
   *
   * @param complete true once every ticket the punch stands for exists
   * @param kotIds the tickets recorded so far; never null
   * @param deltas each line's change at reconcile time; never null
   */
  record Punch(
      String punchId,
      String idempotencyKey,
      boolean complete,
      List<String> kotIds,
      List<Delta> deltas) {

    public Punch {
      kotIds = kotIds == null ? List.of() : List.copyOf(kotIds);
      deltas = deltas == null ? List.of() : List.copyOf(deltas);
    }
  }

  /**
   * One line's change at reconcile time. A negative quantity means cancel that many.
   *
   * @param department the station frozen onto the line; may be blank
   */
  record Delta(String sellableRef, String name, String department, String note, int quantity) {}
}
