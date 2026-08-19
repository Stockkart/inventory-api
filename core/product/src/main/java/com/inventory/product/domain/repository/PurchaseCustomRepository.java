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
   * <p>The four identifying arguments are alternatives, not a conjunction: a
   * purchase qualifies if it carries a matching {@code sellableRef}, a matching
   * legacy {@code inventoryId} or {@code menuItemId}, or an item recorded under
   * one of the product names. Which of them are populated depends on what is
   * being scanned and on how old the sale is, so the criteria are assembled per
   * call.
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
      Collection<String> lotIds,
      Collection<String> menuItemIds,
      Collection<String> productNames,
      int limit);
}
