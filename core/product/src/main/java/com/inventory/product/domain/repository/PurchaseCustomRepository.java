package com.inventory.product.domain.repository;

import java.util.Collection;
import java.util.List;

import com.inventory.product.domain.model.Purchase;

/**
 * Purchase queries whose shape is decided at runtime and so cannot be expressed
 * as a derived method.
 */
public interface PurchaseCustomRepository {

  /**
   * A customer's most recent completed purchases that mention any of the given
   * sale lines, newest first.
   *
   * <p>The identifying arguments are alternatives, not a conjunction: a purchase
   * qualifies if it carries a matching {@code sellableRef}, a matching
   * {@code menuItemId}, or an item recorded under one of the product names.
   * Which of them are populated depends on what is being scanned, so the
   * criteria are assembled per call.
   *
   * <p>Lots are deliberately absent. A lot is one delivery and is replaced
   * whenever stock is received, so selecting on it returns only sales of the
   * batch in hand and hides every earlier one.
   *
   * @param productNames matches items by recorded name, which is the only route
   *     to a sale whose lot no longer exists
   * @param limit hard cap on purchases examined, so a long-standing customer
   *     never causes an unbounded scan
   */
  List<Purchase> findRecentForCustomerMatching(
      String shopId,
      String customerId,
      String excludePurchaseId,
      Collection<String> sellableRefs,
      Collection<String> menuItemIds,
      Collection<String> productNames,
      int limit);
}
