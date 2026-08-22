package com.inventory.product.domain.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.inventory.product.domain.model.Purchase;

/**
 * Purchase queries whose shape is decided at runtime and so cannot be expressed
 * as a derived method.
 */
public interface PurchaseCustomRepository {

  /**
   * A page of the shop's sales narrowed by any combination of invoice number,
   * date and customer.
   *
   * <p>Every criterion is optional and they combine with AND. The search is a
   * single query so that it sees the whole history: filtering a fixed window of
   * recent sales instead can only ever find what happens to fall inside it, and
   * an invoice older than the window is reported as not existing.
   *
   * @param invoiceNo matched as a case-insensitive substring, so a partial
   *     number typed at the counter finds the invoice
   * @param soldFrom inclusive lower bound on {@code soldAt}
   * @param soldTo exclusive upper bound, so a caller passing the day after
   *     covers the whole of the last day
   * @param customerIds restricts to these customers when non-empty
   */
  Page<Purchase> search(
      String shopId,
      String invoiceNo,
      Instant soldFrom,
      Instant soldTo,
      Collection<String> customerIds,
      Pageable pageable);
}
